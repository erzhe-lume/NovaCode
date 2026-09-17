package com.novacode.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novacode.tool.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 会话任务清单（TodoWrite）：Agent 维护一份结构化任务列表，全量替换式更新。
 *
 * <p>状态存内存 + 持久化到 {@code .novacode/todos.json}（尽力而为，失败不影响功能），
 * 进程重启后可恢复上次清单。权限分类为 INTERNAL —— 免确认、Plan Mode 可用，
 * 模型可以随时 freely 更新计划。</p>
 */
public class TodoTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_STATUS =
            Set.of("pending", "in_progress", "completed");

    /** 存储路径在构造时按 user.dir 解析（非 static：测试环境可隔离）。 */
    private final Path store;

    /** 当前任务清单（有序，全量替换）。 */
    private final List<Map<String, String>> todos = new ArrayList<>();

    public TodoTool() {
        this(Path.of(System.getProperty("user.dir"), ".novacode", "todos.json"));
    }

    /** 测试用：显式指定存储路径。 */
    public TodoTool(Path store) {
        this.store = store;
        load();
    }

    @Override public String name() { return "TodoWrite"; }
    @Override public ToolCategory category() { return ToolCategory.INTERNAL; }

    @Override
    public String description() {
        return "Create and update a structured task list for the current session. "
                + "Each call REPLACES the whole list — send every task with its current status.\n"
                + "When to use: any task with 3+ steps, multiple files, or non-trivial scope. "
                + "Do NOT use for a single trivial action.\n"
                + "How to use:\n"
                + "1. Before starting work, write out all known steps (status=pending).\n"
                + "2. Mark exactly ONE task in_progress right before starting it — never batch-complete.\n"
                + "3. Mark a task completed IMMEDIATELY after it is actually done and verified — "
                + "do not finish a session with all tasks in_progress.\n"
                + "4. Add newly discovered steps as you go; remove tasks that turn out unnecessary.\n"
                + "5. Keep each task concrete and verifiable (file-level, action-level), not vague goals.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "todos", Map.of(
                    "type", "array",
                    "description", "The full task list, replacing the previous one",
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "content", Map.of("type", "string",
                                "description", "Imperative one-line description of the task"),
                            "status", Map.of("type", "string",
                                "enum", List.of("pending", "in_progress", "completed"),
                                "description", "Task state")
                        ),
                        "required", List.of("content", "status")
                    )
                )
            ),
            "required", List.of("todos")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object raw = args.get("todos");
        if (!(raw instanceof List<?> list)) {
            return ToolResult.error("Error: todos must be an array of {content, status}");
        }

        List<Map<String, String>> next = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ToolResult.error("Error: each todo must be an object with content and status");
            }
            String content = String.valueOf(m.get("content")).trim();
            Object statusRaw = m.get("status");
            String status = (statusRaw == null ? "pending" : String.valueOf(statusRaw)).trim();
            if (content.isEmpty()) return ToolResult.error("Error: todo content must not be empty");
            if (!VALID_STATUS.contains(status)) {
                return ToolResult.error("Error: invalid status '" + status
                        + "' (expected pending | in_progress | completed)");
            }
            next.add(Map.of("content", content, "status", status));
        }
        if (next.stream().filter(t -> "in_progress".equals(t.get("status"))).count() > 1) {
            return ToolResult.error("Error: only one task may be in_progress at a time — "
                    + "pick the one you are actually working on now");
        }

        todos.clear();
        todos.addAll(next);
        persist();

        return ToolResult.success(render());
    }

    /** 当前清单快照（测试可见；渲染走 execute 的返回值）。 */
    List<Map<String, String>> snapshot() {
        return List.copyOf(todos);
    }

    /** 状态栏进度摘要：{@code "2/5"}；清单为空返回 null。 */
    public String progressSummary() {
        if (todos.isEmpty()) return null;
        long done = todos.stream().filter(t -> "completed".equals(t.get("status"))).count();
        return done + "/" + todos.size();
    }

    private String render() {
        if (todos.isEmpty()) return "Task list cleared.";
        var sb = new StringBuilder("Task list updated (").append(todos.size()).append("):");
        long done = todos.stream().filter(t -> "completed".equals(t.get("status"))).count();
        for (var t : todos) {
            String mark = switch (t.get("status")) {
                case "completed" -> "[x]";
                case "in_progress" -> "[>]";
                default -> "[ ]";
            };
            sb.append('\n').append(mark).append(' ').append(t.get("content"));
        }
        sb.append("\n(").append(done).append('/').append(todos.size()).append(" completed)");
        return sb.toString();
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(store)) return;
        try {
            List<Map<String, String>> loaded = MAPPER.readValue(store.toFile(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            todos.clear();
            for (var m : loaded) {
                String content = String.valueOf(m.get("content"));
                String status = String.valueOf(m.get("status"));
                if (!content.isBlank() && VALID_STATUS.contains(status)) {
                    todos.add(Map.of("content", content, "status", status));
                }
            }
        } catch (Exception ignored) {
            // 清单损坏：视为空清单，工具照常可用
        }
    }

    /** 尽力持久化：写临时文件再原子替换，失败静默（清单仍在内存中可用）。 */
    private void persist() {
        try {
            Files.createDirectories(store.getParent());
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(todos), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }
}
