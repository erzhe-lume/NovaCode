package com.novacode.subagent;

import com.novacode.permission.PermissionMode;

import java.util.List;

/**
 * 子 Agent 角色定义（第 13 章）。
 *
 * <p>由 Markdown + YAML frontmatter 描述（见 {@link AgentLoader}），内置三种角色。
 * {@code permissionMode == null} 表示继承父 Agent 的权限模式（{@link #inheritsMode()}）；
 * 显式指定则固定该模式。</p>
 */
public record SubAgentSpec(
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        String systemPrompt,
        int maxTurns,
        String model,
        PermissionMode permissionMode,
        Isolation isolation
) {

    /** 便捷构造：隔离模式默认 {@link Isolation#NONE}。 */
    public SubAgentSpec(String name, String description, List<String> tools, List<String> disallowedTools,
            String systemPrompt, int maxTurns, String model, PermissionMode permissionMode) {
        this(name, description, tools, disallowedTools, systemPrompt, maxTurns, model, permissionMode, Isolation.NONE);
    }

    /** 是否继承父 Agent 权限模式（permissionMode 为 null）。 */
    public boolean inheritsMode() {
        return permissionMode == null;
    }

    private static final String PLAN_AGENT_SYSTEM_PROMPT = """
            You are a software architect and planning specialist.

            === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
            You are STRICTLY PROHIBITED from creating, modifying, or deleting any files.
            Your role is EXCLUSIVELY to explore code and design implementation plans.

            ## Your Process
            1. Understand the user's request.
            2. Explore with ReadFile / Grep / Glob (Bash read-only only).
            3. Design a concrete approach, follow existing patterns.
            4. Detail step-by-step strategy with file dependencies.

            ## Required Output
            End with "### Critical Files for Implementation" listing key files.""";

    /** 通用子 Agent：继承父权限，全工具（过滤后）。 */
    public static final SubAgentSpec GENERAL_PURPOSE = new SubAgentSpec(
            "general-purpose",
            "General-purpose agent for research and multi-step tasks",
            List.of(), List.of(), null, 0, null, null);

    /** 计划子 Agent：只读，产出实现计划。 */
    public static final SubAgentSpec PLAN = new SubAgentSpec(
            "plan",
            "Software architect for designing implementation plans (read-only)",
            List.of(), List.of("WriteFile", "EditFile"), PLAN_AGENT_SYSTEM_PROMPT, 15, null,
            PermissionMode.PLAN);

    /** 探查子 Agent：只读 + 轻量模型，定位代码。 */
    public static final SubAgentSpec EXPLORE = new SubAgentSpec(
            "explore",
            "Fast read-only search agent for locating code",
            List.of(), List.of("WriteFile", "EditFile"), null, 0, "haiku",
            PermissionMode.PLAN);
}
