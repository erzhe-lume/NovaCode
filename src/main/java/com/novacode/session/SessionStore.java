package com.novacode.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novacode.model.ChatMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 会话存档（第 9 章 F4/F5/F6）。
 *
 * <p>每个会话一个 JSONL 文件，追加写；崩溃最多丢最后一行。不单独维护 meta 文件，
 * 需要 ID / 标题 / 消息数时直接扫 JSONL 现算。恢复时逐行读，坏行跳过、工具调用
 * 无对应结果的缺口处截断。超过 30 天未修改的会话文件自动清理。</p>
 */
public class SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSIONS_DIR = ".mewcode/sessions";
    private static final long EXPIRY_DAYS = 30;

    private final Path sessionsDir;
    private String currentId;
    private int writtenCount;

    public SessionStore(Path projectRoot) {
        this.sessionsDir = projectRoot.resolve(SESSIONS_DIR);
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException ignored) {
            // 目录创建失败时，写盘会静默降级
        }
    }

    public record SessionInfo(String id, String firstMessage, int messageCount, long modTime) {}

    public record ResumeResult(boolean found, String id, List<ChatMessage> messages, long lastTimestamp) {}

    private record LoadResult(List<ChatMessage> messages, long lastTs) {}

    // ── 会话 ID ──────────────────────────────────────────────────────────

    /** 生成带随机后缀的会话 ID，格式 {@code yyyyMMdd-HHmmss-xxxx}，防同秒撞车。 */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        byte[] randomBytes = new byte[2];
        new SecureRandom().nextBytes(randomBytes);
        return "%s-%s".formatted(timestamp, java.util.HexFormat.of().formatHex(randomBytes));
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    public String currentId() {
        return currentId;
    }

    public String startNewSession() {
        this.currentId = newId();
        this.writtenCount = 0;
        return currentId;
    }

    /** 追加 {@code history} 中自上次同步后新增的消息，更新已写计数。 */
    public void sync(List<ChatMessage> history) {
        if (history == null || history.size() <= writtenCount) {
            return;
        }
        if (currentId == null) {
            startNewSession();
        }
        for (int i = writtenCount; i < history.size(); i++) {
            append(history.get(i));
        }
        writtenCount = history.size();
    }

    /** 把一条消息作为一行 JSONL 追加到当前会话文件。空字段省略。 */
    private void append(ChatMessage m) {
        if (currentId == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("role", m.getRole().name().toLowerCase());
        line.put("content", m.getContent() == null ? "" : m.getContent());
        line.put("ts", Instant.now().getEpochSecond());
        if (m.hasToolCalls()) {
            List<Map<String, Object>> tcs = new ArrayList<>();
            for (ChatMessage.ToolCall tc : m.getToolCalls()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", tc.id());
                t.put("name", tc.name());
                t.put("arguments", tc.arguments() == null ? Map.of() : tc.arguments());
                tcs.add(t);
            }
            line.put("tool_calls", tcs);
        }
        if (m.getToolCallId() != null) {
            line.put("tool_call_id", m.getToolCallId());
        }
        try {
            Files.writeString(sessionsDir.resolve(currentId + ".jsonl"),
                    MAPPER.writeValueAsString(line) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 尽力而为：会话写盘失败不打断对话
        }
    }

    // ── 恢复 ─────────────────────────────────────────────────────────────

    /** 找最新的会话文件并加载恢复。无文件或空返回 {@code found=false}。
     *  mtime 打平时按会话 ID（内含时间戳）取较大者，保证同粒度创建时恢复最新的。 */
    public ResumeResult resumeMostRecent() {
        Path latest = null;
        long latestMtime = -1;
        String latestName = "";
        try (Stream<Path> paths = Files.list(sessionsDir)) {
            for (Path p : paths.toList()) {
                if (!p.toString().endsWith(".jsonl") || !Files.isRegularFile(p)) continue;
                long mtime = Files.getLastModifiedTime(p).toMillis();
                String name = p.getFileName().toString();
                if (mtime > latestMtime
                        || (mtime == latestMtime && name.compareTo(latestName) > 0)) {
                    latestMtime = mtime;
                    latest = p;
                    latestName = name;
                }
            }
        } catch (IOException ignored) {
            return new ResumeResult(false, null, List.of(), 0L);
        }
        if (latest == null) {
            return new ResumeResult(false, null, List.of(), 0L);
        }
        LoadResult loaded = loadWithTs(latest);
        if (loaded.messages().isEmpty()) {
            return new ResumeResult(false, null, List.of(), 0L);
        }
        String id = latest.getFileName().toString()
                .substring(0, latest.getFileName().toString().length() - ".jsonl".length());
        long lastTs = loaded.lastTs() > 0 ? loaded.lastTs() : latestMtime / 1000;
        currentId = id;
        writtenCount = loaded.messages().size();
        return new ResumeResult(true, id, loaded.messages(), lastTs);
    }

    /** 按 ID（或 ID 前缀）恢复会话（/resume <id>）。找不到或为空返回 {@code found=false}。 */
    public ResumeResult resumeById(String idPrefix) {
        if (idPrefix == null || idPrefix.isBlank()) {
            return new ResumeResult(false, null, List.of(), 0L);
        }
        Path file = null;
        try (Stream<Path> paths = Files.list(sessionsDir)) {
            List<Path> candidates = paths
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
            for (Path p : candidates) {
                String base = p.getFileName().toString()
                        .substring(0, p.getFileName().toString().length() - ".jsonl".length());
                if (base.equals(idPrefix)) { file = p; break; }        // 精确匹配优先
                if (file == null && base.startsWith(idPrefix)) file = p; // 否则取字典序最小的前缀匹配
            }
        } catch (IOException ignored) {
            return new ResumeResult(false, null, List.of(), 0L);
        }
        if (file == null) return new ResumeResult(false, null, List.of(), 0L);

        LoadResult loaded = loadWithTs(file);
        if (loaded.messages().isEmpty()) return new ResumeResult(false, null, List.of(), 0L);
        String resolvedId = file.getFileName().toString()
                .substring(0, file.getFileName().toString().length() - ".jsonl".length());
        currentId = resolvedId;
        writtenCount = loaded.messages().size();
        return new ResumeResult(true, resolvedId, loaded.messages(), loaded.lastTs());
    }

    /** 读取一个会话文件，恢复为消息列表（坏行跳过、缺口截断）。 */
    public static List<ChatMessage> loadSession(Path file) {
        return loadWithTs(file).messages();
    }

    private static LoadResult loadWithTs(Path file) {
        if (!Files.exists(file)) {
            return new LoadResult(List.of(), 0L);
        }
        List<ChatMessage> messages = new ArrayList<>();
        long lastTs = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    ChatMessage m = toMessage(map);
                    if (m != null) {
                        messages.add(m);
                    }
                    if (map.get("ts") instanceof Number n) {
                        lastTs = n.longValue();
                    }
                } catch (IOException ignored) {
                    // 坏行跳过
                }
            }
        } catch (IOException ignored) {
            // 返回已收集的部分
        }
        return new LoadResult(truncateDangling(messages), lastTs);
    }

    /** 把一行 JSONL 反序列化为 ChatMessage；role 未知或空返回 null。 */
    private static ChatMessage toMessage(Map<String, Object> map) {
        String roleStr = (String) map.get("role");
        if (roleStr == null) return null;
        ChatMessage.Role role = switch (roleStr) {
            case "user" -> ChatMessage.Role.USER;
            case "assistant" -> ChatMessage.Role.ASSISTANT;
            case "tool" -> ChatMessage.Role.TOOL;
            case "system" -> ChatMessage.Role.SYSTEM;
            default -> null;
        };
        if (role == null) return null;
        String content = map.get("content") == null ? "" : (String) map.get("content");

        if (role == ChatMessage.Role.TOOL) {
            String toolCallId = map.get("tool_call_id") == null ? null : (String) map.get("tool_call_id");
            return ChatMessage.toolResult(toolCallId, content);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawCalls = (List<Map<String, Object>>) map.get("tool_calls");
        if (rawCalls != null && !rawCalls.isEmpty()) {
            List<ChatMessage.ToolCall> calls = new ArrayList<>();
            for (Map<String, Object> raw : rawCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) raw.get("arguments");
                calls.add(new ChatMessage.ToolCall(
                        (String) raw.get("id"),
                        (String) raw.get("name"),
                        args == null ? Map.of() : args));
            }
            return new ChatMessage(role, content, calls);
        }
        return new ChatMessage(role, content);
    }

    /**
     * 缺口截断：若存在「带 tool_call 但没有对应 TOOL 结果」的 ASSISTANT 消息
     * （崩溃发生在工具执行中途），把列表截到该消息之前，避免回放孤儿调用。
     */
    static List<ChatMessage> truncateDangling(List<ChatMessage> messages) {
        Set<String> resultIds = new HashSet<>();
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.TOOL && m.getToolCallId() != null) {
                resultIds.add(m.getToolCallId());
            }
        }
        int truncateAt = -1;
        outer:
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() == ChatMessage.Role.ASSISTANT && m.hasToolCalls()) {
                for (ChatMessage.ToolCall tc : m.getToolCalls()) {
                    if (!resultIds.contains(tc.id())) {
                        truncateAt = i;
                        break outer;
                    }
                }
            }
        }
        if (truncateAt >= 0) {
            return new ArrayList<>(messages.subList(0, truncateAt));
        }
        return messages;
    }

    // ── 清理 ─────────────────────────────────────────────────────────────

    /** 删除结果：ok=false 时 message 说明原因（找不到/不唯一/当前会话）。 */
    public record DeleteResult(boolean ok, String message) {}

    /** 删除 ID（或前缀）匹配的会话文件。要求恰好匹配一个、且不是当前会话。 */
    public DeleteResult deleteById(String idPrefix) {
        if (idPrefix == null || idPrefix.isBlank()) {
            return new DeleteResult(false, "缺少会话 ID 前缀");
        }
        List<Path> matches = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try (Stream<Path> paths = Files.list(sessionsDir)) {
            for (Path p : paths
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                String base = p.getFileName().toString()
                        .substring(0, p.getFileName().toString().length() - ".jsonl".length());
                if (base.equals(idPrefix) || base.startsWith(idPrefix)) {
                    matches.add(p);
                    ids.add(base);
                }
            }
        } catch (IOException e) {
            return new DeleteResult(false, "扫描会话目录失败");
        }
        if (matches.isEmpty()) return new DeleteResult(false, "没有匹配 " + idPrefix + " 的会话");
        if (matches.size() > 1) {
            return new DeleteResult(false, "前缀匹配到 " + matches.size() + " 个会话，请用更长的 ID（如 "
                    + ids.get(0) + "）");
        }
        String id = ids.get(0);
        if (id.equals(currentId)) {
            return new DeleteResult(false, "不能删除当前会话（先 /new 或 /resume 切走）");
        }
        try {
            Files.deleteIfExists(matches.get(0));
            return new DeleteResult(true, "已删除会话 " + id);
        } catch (IOException e) {
            return new DeleteResult(false, "删除失败: " + e.getMessage());
        }
    }

    /** 删除 mtime 超过 30 天的会话文件。 */
    public void cleanExpired() {
        if (!Files.isDirectory(sessionsDir)) return;
        long cutoffMs = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
        try (Stream<Path> paths = Files.list(sessionsDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         if (Files.getLastModifiedTime(p).toMillis() < cutoffMs) {
                             Files.deleteIfExists(p);
                         }
                     } catch (IOException ignored) {
                         // 单个文件清理失败不影响其它
                     }
                 });
        } catch (IOException ignored) {
            // 目录不可读时静默忽略
        }
    }

    // ── 列表（扫 JSONL 现算，无 meta 文件）─────────────────────────────

    public List<SessionInfo> list() {
        if (!Files.isDirectory(sessionsDir)) return List.of();
        List<SessionInfo> out = new ArrayList<>();
        try (Stream<Path> paths = Files.list(sessionsDir)) {
            for (Path p : paths.toList()) {
                if (!p.toString().endsWith(".jsonl") || !Files.isRegularFile(p)) continue;
                String fileName = p.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                try {
                    List<ChatMessage> msgs = loadSession(p);
                    String first = msgs.stream()
                            .filter(m -> m.getRole() == ChatMessage.Role.USER)
                            .map(ChatMessage::getContent)
                            .findFirst()
                            .orElse("");
                    out.add(new SessionInfo(id, first, msgs.size(),
                            Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                    // 跳过该文件
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        out.sort(Comparator.comparingLong(SessionInfo::modTime).reversed()
                .thenComparing(SessionInfo::id, Comparator.reverseOrder()));
        return out;
    }
}
