package com.novacode.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队共享任务板（第 15 章 F3/F5/N4）。
 *
 * <p>落盘为 {@code <teamDir>/tasks.json}，格式 {@code { next_id, tasks[] }}。每次读操作前
 * 重载磁盘保证跨进程最新（N4）；写操作把整份状态写回。状态枚举
 * {@code pending / in_progress / completed / blocked}；依赖字段 {@code blocks} /
 * {@code blockedBy} 追加去重（本步不做复杂拓扑）。</p>
 */
public final class SharedTaskStore {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_BLOCKED = "blocked";

    /** 单个共享任务。列表字段经紧凑构造器归一化为非 null、去重。 */
    public record SharedTask(String id, String title, String description, String status,
                             String assignee, List<String> blocks, List<String> blockedBy,
                             String createdBy) {
        public SharedTask {
            blocks = blocks == null ? List.of() : blocks.stream().distinct().toList();
            blockedBy = blockedBy == null ? List.of() : blockedBy.stream().distinct().toList();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path tasksFile;
    private List<SharedTask> tasks = new ArrayList<>();
    private int nextId = 1;

    public SharedTaskStore(Path tasksFile) {
        this.tasksFile = tasksFile;
    }

    public static boolean isValidStatus(String s) {
        return STATUS_PENDING.equals(s) || STATUS_IN_PROGRESS.equals(s)
                || STATUS_COMPLETED.equals(s) || STATUS_BLOCKED.equals(s);
    }

    /** 磁盘重载；文件缺失/损坏 → 空板（不落盘）。 */
    public synchronized void load() {
        if (!Files.exists(tasksFile)) {
            tasks = new ArrayList<>();
            nextId = 1;
            return;
        }
        try {
            Map<String, Object> root = JSON.readValue(tasksFile.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            if (root == null) { resetEmpty(); return; }
            this.nextId = (int) num(root, "next_id", 1);
            List<SharedTask> loaded = new ArrayList<>();
            Object o = root.get("tasks");
            if (o instanceof List<?> tl) {
                for (Object item : tl) {
                    if (item instanceof Map<?, ?> im) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mm = (Map<String, Object>) im;
                        SharedTask t = fromMap(mm);
                        if (t != null) loaded.add(t);
                    }
                }
            }
            this.tasks = loaded;
        } catch (Exception e) {
            resetEmpty();
        }
    }

    /** 清空并写盘。 */
    public synchronized void initEmpty() {
        resetEmpty();
        persist();
    }

    private void resetEmpty() {
        tasks = new ArrayList<>();
        nextId = 1;
    }

    /** 新建任务：id 自增、status=pending、写盘。 */
    public synchronized SharedTask create(String title, String description, String assignee,
                                          List<String> blocks, List<String> blockedBy, String createdBy) {
        load();
        String id = "task-" + nextId++;
        SharedTask t = new SharedTask(id, title, description, STATUS_PENDING, assignee,
                blocks, blockedBy, createdBy);
        tasks.add(t);
        persist();
        return t;
    }

    /** 按 id 查任务；不存在返回 null。 */
    public synchronized SharedTask get(String id) {
        load();
        for (SharedTask t : tasks) if (t.id().equals(id)) return t;
        return null;
    }

    /** 按状态/归属过滤（null 或空 = 不过滤）。 */
    public synchronized List<SharedTask> listTasks(String status, String assignee) {
        load();
        return tasks.stream()
                .filter(t -> status == null || status.isBlank() || status.equals(t.status()))
                .filter(t -> assignee == null || assignee.isBlank() || assignee.equals(t.assignee()))
                .toList();
    }

    /** 更新任务；返回更新后任务或 null。status 非空时校验合法性（非法抛异常）。 */
    public synchronized SharedTask update(String id, String status, String assignee, String desc,
                                          List<String> addBlocks, List<String> addBlockedBy) {
        load();
        for (int i = 0; i < tasks.size(); i++) {
            SharedTask t = tasks.get(i);
            if (!t.id().equals(id)) continue;
            String ns = (status != null && !status.isBlank()) ? status : t.status();
            if (!isValidStatus(ns)) {
                throw new IllegalArgumentException("非法任务状态: " + ns);
            }
            String na = (assignee != null && !assignee.isBlank()) ? assignee : t.assignee();
            String nd = desc != null ? desc : t.description();
            List<String> nb = new ArrayList<>(t.blocks());
            if (addBlocks != null) {
                for (String b : addBlocks) if (b != null && !nb.contains(b)) nb.add(b);
            }
            List<String> nbb = new ArrayList<>(t.blockedBy());
            if (addBlockedBy != null) {
                for (String b : addBlockedBy) if (b != null && !nbb.contains(b)) nbb.add(b);
            }
            SharedTask nt = new SharedTask(id, t.title(), nd, ns, na, nb, nbb, t.createdBy());
            tasks.set(i, nt);
            persist();
            return nt;
        }
        return null;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private void persist() {
        try {
            Path parent = tasksFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("next_id", nextId);
            List<Map<String, Object>> list = new ArrayList<>();
            for (SharedTask t : tasks) list.add(toMap(t));
            root.put("tasks", list);
            JSON.writerWithDefaultPrettyPrinter().writeValue(tasksFile.toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("task store write failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> toMap(SharedTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("title", t.title());
        m.put("description", t.description());
        m.put("status", t.status());
        m.put("assignee", t.assignee());
        m.put("blocks", t.blocks());
        m.put("blocked_by", t.blockedBy());
        m.put("created_by", t.createdBy());
        return m;
    }

    private static SharedTask fromMap(Map<String, Object> m) {
        String id = str(m, "id");
        String title = str(m, "title");
        if (id == null || title == null) return null;
        String status = str(m, "status");
        if (status == null || !isValidStatus(status)) status = STATUS_PENDING;
        return new SharedTask(id, title, str(m, "description"), status, str(m, "assignee"),
                strList(m, "blocks"), strList(m, "blocked_by"), str(m, "created_by"));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s ? s : null;
    }

    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof List<?> l)) return List.of();
        return l.stream().filter(String.class::isInstance).map(String.class::cast)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static long num(Map<String, Object> m, String k, long dflt) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : dflt;
    }
}
