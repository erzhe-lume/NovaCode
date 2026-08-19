package com.novacode.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队配置磁盘形态（第 15 章 F1/N1/N4）。
 *
 * <p>纯数据 DTO，跨进程/跨重启共享。落盘到 {@code <teamDir>/config.json}（snake_case
 * 字段）。团队名经 {@link #sanitizeTeamName} 清洗成文件系统安全的小写 slug。</p>
 */
public final class TeamFile {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 队员元数据（不持有运行态句柄）。 */
    public record MemberInfo(String name, String agentType, String model,
                             String worktreePath, boolean needsApproval, long joinedAt) {}

    /** 团队配置快照。 */
    public record TeamData(String name, String mode, String leadAgentId, String description,
                           long createdAt, List<MemberInfo> members) {}

    private TeamFile() {}

    /** 团队名 → 文件系统安全 slug：非字母数字替换为 {@code -} 并转小写；空/全无效 → untitled。 */
    public static String sanitizeTeamName(String name) {
        if (name == null || name.isBlank()) return "untitled";
        String slug = name.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
        return slug.isBlank() ? "untitled" : slug;
    }

    public static void write(Path configPath, TeamData data) {
        try {
            Path parent = configPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            JSON.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), toMap(data));
        } catch (Exception e) {
            throw new IllegalStateException("team config write failed: " + e.getMessage());
        }
    }

    /** 读团队配置；文件缺失/损坏返回 null。 */
    public static TeamData read(Path configPath) {
        try {
            if (!Files.exists(configPath)) return null;
            Map<String, Object> m = JSON.readValue(configPath.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            return fromMap(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> toMap(TeamData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("mode", d.mode());
        m.put("lead_agent_id", d.leadAgentId());
        m.put("description", d.description());
        m.put("created_at", d.createdAt());
        List<Map<String, Object>> members = new ArrayList<>();
        if (d.members() != null) {
            for (MemberInfo mi : d.members()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", mi.name());
                mm.put("agent_type", mi.agentType());
                mm.put("model", mi.model());
                mm.put("worktree_path", mi.worktreePath());
                mm.put("needs_approval", mi.needsApproval());
                mm.put("joined_at", mi.joinedAt());
                members.add(mm);
            }
        }
        m.put("members", members);
        return m;
    }

    private static TeamData fromMap(Map<String, Object> m) {
        if (m == null) return null;
        String name = str(m, "name");
        if (name == null) return null;
        List<MemberInfo> members = new ArrayList<>();
        Object o = m.get("members");
        if (o instanceof List<?> ml) {
            for (Object item : ml) {
                if (item instanceof Map<?, ?> im) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) im;
                    MemberInfo mi = new MemberInfo(
                            str(mm, "name"),
                            str(mm, "agent_type"),
                            str(mm, "model"),
                            str(mm, "worktree_path"),
                            Boolean.TRUE.equals(mm.get("needs_approval")),
                            num(mm, "joined_at"));
                    members.add(mi);
                }
            }
        }
        return new TeamData(name, str(m, "mode"), str(m, "lead_agent_id"),
                str(m, "description"), num(m, "created_at"), members);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s ? s : null;
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
