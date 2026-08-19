package com.novacode.memory;

import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;
import com.novacode.protocol.LlmClient;
import com.novacode.protocol.StreamEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 自动笔记 + 记忆索引（第 9 章 F7/F8/F9）。
 *
 * <p>笔记分四类：user（用户偏好）/ feedback（纠正反馈）→ 用户级目录
 * {@code ~/.mewcode/memory/}；project（项目知识）/ reference（参考资料）→ 项目级目录
 * {@code .mewcode/memory/}。每条笔记一个带 frontmatter 的 Markdown，另有一份
 * MEMORY.md 索引。索引在请求处理前注入 System Prompt；每轮自然结束后异步调 LLM
 * 提取笔记，去重交给 LLM 判断。</p>
 */
public class MemoryManager {

    /** MEMORY.md 索引文件名 */
    public static final String ENTRYPOINT_NAME = "MEMORY.md";
    /** 索引注入上下文时的行数 / 字节上限 */
    public static final int MAX_ENTRYPOINT_LINES = 200;
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    private static final String MEMORY_DIR = ".mewcode/memory";
    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    private static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private static final String NOTE_SYSTEM_PROMPT = """
            你是一个记忆提取助手。分析下面的对话，提取值得长期记住的信息，写成笔记。

            对每条值得记住的信息，严格按以下格式输出一个块，块与块之间用单独一行 `---` 分隔：
            MEMORY_NAME: <kebab-case 短名>
            MEMORY_TYPE: <user|feedback|project|reference>
            MEMORY_DESC: <一行描述>
            MEMORY_BODY: <正文，可多行>

            type 含义：
            - user：用户的偏好、习惯、背景
            - feedback：用户对「如何工作」的纠正或确认
            - project：项目知识（技术栈、架构、约定、决策）
            - reference：外部资源指针（URL、文档、票据）

            不要记的：
            - 从代码或仓库就能直接读到的模式
            - 一次性任务细节、临时调试过程

            先看「已有记忆」清单做去重：已有的事实就更新同名文件，不要新建重复条目。
            如果没有任何值得记的信息，只输出 NONE。
            """;

    private final Path userMemDir;
    private final Path projectMemDir;
    private final LlmClient noteClient;

    public MemoryManager(Path projectRoot, ProviderConfig config) {
        this.projectMemDir = projectRoot.resolve(MEMORY_DIR);
        this.userMemDir = Path.of(System.getProperty("user.home"), MEMORY_DIR);
        ensureDir(userMemDir);
        ensureDir(projectMemDir);
        this.noteClient = LlmClient.create(config, null); // 无工具客户端
    }

    // ── 索引注入 ─────────────────────────────────────────────────────────

    /** 读取两份 MEMORY.md 拼成记忆索引段，注入 System Prompt（F9）。 */
    public String buildMemorySection() {
        ensureDir(userMemDir);
        ensureDir(projectMemDir);
        var sb = new StringBuilder();
        sb.append("# auto memory\n\n");
        appendEntrypoint(sb, "User-level", userMemDir);
        sb.append("\n\n");
        appendEntrypoint(sb, "Project-level", projectMemDir);
        return sb.toString();
    }

    private static void appendEntrypoint(StringBuilder sb, String scopeLabel, Path memDir) {
        Path ep = memDir.resolve(ENTRYPOINT_NAME);
        sb.append("## %s %s (`%s`)\n\n".formatted(scopeLabel, ENTRYPOINT_NAME, ep));
        try {
            String content = Files.readString(ep).strip();
            if (!content.isEmpty()) {
                sb.append(truncateEntrypointContent(content));
            } else {
                sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
            }
        } catch (IOException e) {
            sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
        }
    }

    /** 把 MEMORY.md 内容截到 200 行 / 25KB 内，超限附警告。 */
    static String truncateEntrypointContent(String raw) {
        String trimmed = raw.strip();
        String[] lines = trimmed.split("\n", -1);
        int lineCount = lines.length;
        int byteCount = trimmed.getBytes(StandardCharsets.UTF_8).length;

        boolean overLines = lineCount > MAX_ENTRYPOINT_LINES;
        boolean overBytes = byteCount > MAX_ENTRYPOINT_BYTES;
        if (!overLines && !overBytes) {
            return trimmed;
        }

        String result = trimmed;
        if (overLines) {
            result = String.join("\n", Arrays.copyOfRange(lines, 0, MAX_ENTRYPOINT_LINES));
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENTRYPOINT_BYTES) {
            result = cutToBytes(bytes, MAX_ENTRYPOINT_BYTES);
        }

        String reason = overBytes && overLines
                ? "%d lines and %s".formatted(lineCount, formatFileSize(byteCount))
                : overBytes
                ? "%s (limit: %s) — index entries are too long".formatted(formatFileSize(byteCount), formatFileSize(MAX_ENTRYPOINT_BYTES))
                : "%d lines (limit: %d)".formatted(lineCount, MAX_ENTRYPOINT_LINES);

        return result + ("\n\n> WARNING: %s is %s. Only part of it was loaded. "
                + "Keep index entries to one line under ~200 chars; move detail into topic files.")
                .formatted(ENTRYPOINT_NAME, reason);
    }

    /** 按 UTF-8 字节截断，优先切在换行处，否则回退到字符边界。 */
    private static String cutToBytes(byte[] bytes, int limit) {
        for (int i = limit - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return new String(bytes, 0, i, StandardCharsets.UTF_8);
            }
        }
        int end = limit;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static String formatFileSize(int bytes) {
        if (bytes < 1024) return "%dB".formatted(bytes);
        if (bytes < 1024 * 1024) return "%.1fKB".formatted(bytes / 1024.0);
        return "%.1fMB".formatted(bytes / 1024.0 / 1024.0);
    }

    // ── 异步提取 ─────────────────────────────────────────────────────────

    /** 快照历史后在虚拟线程上提取笔记，不阻塞 Agent 循环 / UI（N1）。 */
    public void extractAsync(List<ChatMessage> history) {
        var snapshot = new ArrayList<>(history);
        Thread.startVirtualThread(() -> {
            try {
                extract(snapshot);
            } catch (Exception ignored) {
                // 笔记提取失败静默，不影响对话
            }
        });
    }

    private void extract(List<ChatMessage> history) {
        if (history.size() < 4) return;

        int start = Math.max(0, history.size() - 40);
        var transcript = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m.getContent() == null || m.getContent().isEmpty()) continue;
            transcript.append('[').append(m.getRole().name().toLowerCase())
                    .append("]: ").append(m.getContent()).append('\n');
        }
        if (transcript.isEmpty()) return;

        String manifest = scanExistingMemories();
        String manifestSection = manifest.isEmpty() ? ""
                : "\n\n## 已有记忆\n\n" + manifest
                + "\n\n去重：优先更新已有文件，不要新建重复条目。";

        String userMsg = "从下面的对话提取值得记住的笔记。\n\n"
                + "对话：\n" + transcript + manifestSection;

        List<ChatMessage> call = List.of(new ChatMessage(ChatMessage.Role.USER, userMsg));
        String output = ask(call);
        if (output == null || output.isBlank() || "NONE".equals(output.trim())) return;
        if (!output.contains("MEMORY_NAME:")) return;

        for (String block : output.split("---")) {
            if (!block.contains("MEMORY_NAME:")) continue;
            String name = field(block, "MEMORY_NAME");
            String type = field(block, "MEMORY_TYPE");
            String desc = field(block, "MEMORY_DESC");
            String body = bodyField(block);
            if (name.isEmpty() || body.isEmpty()) continue;
            if (!USER_TYPES.contains(type) && !PROJECT_TYPES.contains(type)) {
                type = "reference";
            }
            Path targetDir = USER_TYPES.contains(type) ? userMemDir : projectMemDir;
            writeMemoryFile(targetDir, name, type, desc, body);
        }
    }

    /** 发起一次流式 LLM 调用，收集文本；出错或超时返回 null。 */
    private String ask(List<ChatMessage> messages) {
        BlockingQueue<StreamEvent> queue = noteClient.stream(messages, NOTE_SYSTEM_PROMPT);
        var sb = new StringBuilder();
        while (true) {
            StreamEvent evt;
            try {
                evt = queue.poll(90, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (evt == null) return null;
            switch (evt) {
                case StreamEvent.TextDelta td -> sb.append(td.text());
                case StreamEvent.ThinkingDelta ignored -> {}
                case StreamEvent.StreamEnd se -> { return sb.toString(); }
                case StreamEvent.Error err -> { return null; }
                default -> {}
            }
        }
    }

    // ── 笔记文件与索引 ───────────────────────────────────────────────────

    /** 写一条笔记为 frontmatter Markdown，并追加 MEMORY.md 指针（不重复）。 */
    private void writeMemoryFile(Path dir, String name, String type, String description, String body) {
        ensureDir(dir);
        String filename = name + ".md";
        Path filePath = dir.resolve(filename);
        String fileContent = "---\nname: %s\ndescription: %s\nmetadata:\n  type: %s\n---\n\n%s\n"
                .formatted(name, description, type, body);
        try {
            Files.writeString(filePath, fileContent,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            return;
        }

        Path entrypoint = dir.resolve(ENTRYPOINT_NAME);
        String pointer = "- [%s](%s) — %s\n".formatted(name, filename, description);
        try {
            String existing = Files.exists(entrypoint) ? Files.readString(entrypoint) : "";
            if (!existing.contains(filename)) {
                Files.writeString(entrypoint, existing + pointer,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
        }
    }

    /** 扫两个目录的笔记 frontmatter，拼成去重用 manifest。 */
    private String scanExistingMemories() {
        var entries = new ArrayList<String>();
        for (Path dir : List.of(userMemDir, projectMemDir)) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".md")
                                && !f.getFileName().toString().equals(ENTRYPOINT_NAME))
                     .sorted()
                     .forEach(f -> {
                         try {
                             String content = Files.readString(f);
                             String type = field(content, "type");
                             String desc = field(content, "description");
                             if (type.isEmpty()) type = "?";
                             if (desc.isEmpty()) desc = f.getFileName().toString();
                             entries.add("- [%s] %s: %s".formatted(type, f.getFileName(), desc));
                         } catch (IOException ignored) {
                         }
                     });
            } catch (IOException ignored) {
            }
        }
        return String.join("\n", entries);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** 提取单行字段（name/type/desc）；值截到行尾。 */
    private static String field(String block, String name) {
        var m = Pattern.compile(name + ":\\s*([^\\r\\n]*)").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 提取 MEMORY_BODY（可多行，直到块尾）。 */
    private static String bodyField(String block) {
        var m = Pattern.compile("MEMORY_BODY:\\s*([\\s\\S]*)").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    private static void ensureDir(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
    }
}
