package com.novacode.worktree;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Worktree 会话 JSON 持久化（第 14 章 F3）。当前活动会话存于
 * {@code <gitRoot>/.novacode/worktree_session.json}。读写全部容错：异常返回 null / 静默。
 */
public final class WorktreeSessionStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorktreeSessionStore() {}

    public static Path defaultPath(Path gitRoot) {
        return gitRoot.resolve(".novacode").resolve("worktree_session.json");
    }

    public static void save(Path storePath, WorktreeSession s) {
        try {
            Path parent = storePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            JSON.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), toMap(s));
        } catch (Exception ignored) {}
    }

    public static WorktreeSession load(Path storePath) {
        try {
            if (!Files.exists(storePath)) return null;
            Map<String, Object> m = JSON.readValue(storePath.toFile(), new TypeReference<Map<String, Object>>() {});
            return fromMap(m);
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Path storePath) {
        try {
            Files.deleteIfExists(storePath);
        } catch (Exception ignored) {}
    }

    private static Map<String, Object> toMap(WorktreeSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originalCwd", s.originalCwd() == null ? null : s.originalCwd().toString());
        m.put("worktreePath", s.worktreePath() == null ? null : s.worktreePath().toString());
        m.put("worktreeName", s.worktreeName());
        m.put("worktreeBranch", s.worktreeBranch());
        m.put("originalBranch", s.originalBranch());
        m.put("originalHeadCommit", s.originalHeadCommit());
        m.put("sessionId", s.sessionId());
        m.put("creationDurationMs", s.creationDurationMs());
        return m;
    }

    private static WorktreeSession fromMap(Map<String, Object> m) {
        if (m == null) return null;
        return new WorktreeSession(
                path(m, "originalCwd"),
                path(m, "worktreePath"),
                str(m, "worktreeName"),
                str(m, "worktreeBranch"),
                str(m, "originalBranch"),
                str(m, "originalHeadCommit"),
                str(m, "sessionId"),
                num(m, "creationDurationMs"));
    }

    private static Path path(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : Path.of(s);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s ? s : null;
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : -1L;
    }
}
