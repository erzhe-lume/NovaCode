package com.novacode.teams;

import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中止队员工具（第 15 章 F7/AC5）。
 *
 * <p>跨团队按成员名查找：不在跑返回提示（成功），在跑则打断其循环线程。fail-safe——
 * 任何异常转错误结果。只给 Lead（在 {@code ALWAYS_DISALLOWED} 中，队员拿不到）。</p>
 */
public final class TaskStopTool implements Tool {

    private final TeamManager teamManager;

    public TaskStopTool(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    @Override public String name() { return "TaskStop"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }
    @Override public String description() {
        return "Stop a running team member by member name. Idempotent: stopping an idle member is a no-op.";
    }
    @Override public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("agent_id", Map.of("type", "string", "description", "Team member name to stop."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("agent_id"));
        return TeamSchemas.wrap(name(), description(), schema);
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        Object v = args.get("agent_id");
        String agentId = v instanceof String s ? s : null;
        if (agentId == null || agentId.isBlank()) {
            return ToolResult.error("Error: 'agent_id' is required");
        }
        for (TeamManager.Team team : teamManager.teams()) {
            TeamManager.Member m = team.getMember(agentId);
            if (m == null) continue;
            if (!m.active) {
                return ToolResult.success("member " + agentId + " is not running (idle)");
            }
            team.stopMember(m);
            return ToolResult.success("stopping member " + agentId);
        }
        return ToolResult.success("member " + agentId + " is not running (not found)");
    }
}
