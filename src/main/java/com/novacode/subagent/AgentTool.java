package com.novacode.subagent;

import com.novacode.model.ChatMessage;
import com.novacode.permission.PermissionMode;
import com.novacode.teams.SpawnDispatcher;
import com.novacode.teams.TeamManager;
import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolRegistry;
import com.novacode.tool.ToolResult;
import com.novacode.worktree.WorktreeService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 统一「Agent」工具（第 13 章 F1）：用 {@code subagent_type} 参数分流两条路径 ——
 * <ul>
 *   <li><b>定义式</b>（有 {@code subagent_type}）：从空白对话 + 固定角色启动，
 *       按 {@link ToolFilter} 过滤工具；同步或后台（{@code run_in_background}）。</li>
 *   <li><b>Fork 式</b>（无 {@code subagent_type}）：继承父对话历史与工具集，强制后台，
 *       复用父 system prompt 保 prompt cache 命中；嵌套 fork 经 {@code querySource}
 *       标记 + 历史扫 {@code <fork_boilerplate>} 标签双重拦截。</li>
 * </ul>
 * 前台跑太久（{@code timeout_ms}）自动转后台续跑，不丢已产出。
 */
public class AgentTool implements Tool {

    private static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    private static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. Final report must be under 500 characters, starting with "Scope:".
            </fork_boilerplate>""";

    /** fork 子 Agent 的 querySource 标记值，用于运行时拦截嵌套 fork。 */
    private static final String FORK_QUERY_SOURCE = "agent:builtin:fork";

    /** fork 关闭时，省略 subagent_type 的回退目标。 */
    private static final String GENERAL_PURPOSE_AGENT_TYPE = "general-purpose";

    private final ToolRegistry parentRegistry;
    private final SubAgentRunner runner;

    private Map<String, SubAgentSpec> agentSpecs = Map.of();
    private SubAgentTaskManager taskManager;
    private Supplier<PermissionMode> parentModeSupplier = () -> PermissionMode.DEFAULT;
    private Supplier<List<ChatMessage>> parentHistorySupplier;
    private Supplier<String> parentSystemPromptSupplier;
    private boolean forkDisabled = false;
    private String querySource = "";
    private WorktreeService worktreeService;
    private TeamManager teamManager;

    public AgentTool(ToolRegistry parentRegistry, SubAgentRunner runner) {
        this.parentRegistry = parentRegistry;
        this.runner = runner;
    }

    // ── 注入 ─────────────────────────────────────────────────────────────

    public void setAgentSpecs(Map<String, SubAgentSpec> agentSpecs) { this.agentSpecs = agentSpecs; }
    public void setTaskManager(SubAgentTaskManager taskManager) { this.taskManager = taskManager; }
    public void setParentModeSupplier(Supplier<PermissionMode> s) { this.parentModeSupplier = s; }
    public void setParentHistorySupplier(Supplier<List<ChatMessage>> s) { this.parentHistorySupplier = s; }
    public void setParentSystemPromptSupplier(Supplier<String> s) { this.parentSystemPromptSupplier = s; }
    public void setForkDisabled(boolean forkDisabled) { this.forkDisabled = forkDisabled; }
    /** 注入 worktree 服务（第 14 章）；为 null 时隔离角色退化为共享工作目录。 */
    public void setWorktreeService(WorktreeService s) { this.worktreeService = s; }
    /** 注入团队管理器（第 15 章）；team_name 参数依赖它。 */
    public void setTeamManager(TeamManager tm) { this.teamManager = tm; }

    /** 模型别名解析（"haiku"/"sonnet"/"opus" → LlmClient），委托给共享 runner。 */
    public void setModelResolver(Function<String, com.novacode.protocol.LlmClient> r) { runner.setModelResolver(r); }
    /** 子 Agent 进度回调，委托给共享 runner。 */
    public void setProgressListener(Consumer<SubAgentProgress> l) { runner.setProgressListener(l); }

    public String getQuerySource() { return querySource; }

    /** 浅复制并打上 querySource 标记（fork 用它拦截嵌套 fork）。 */
    public AgentTool cloneWithQuerySource(String qs) {
        AgentTool clone = new AgentTool(this.parentRegistry, this.runner);
        clone.agentSpecs = this.agentSpecs;
        clone.taskManager = this.taskManager;
        clone.parentModeSupplier = this.parentModeSupplier;
        clone.parentHistorySupplier = this.parentHistorySupplier;
        clone.parentSystemPromptSupplier = this.parentSystemPromptSupplier;
        clone.forkDisabled = this.forkDisabled;
        clone.worktreeService = this.worktreeService;
        clone.teamManager = this.teamManager;
        clone.querySource = qs;
        return clone;
    }

    // ── Tool 接口 ────────────────────────────────────────────────────────

    @Override public String name() { return "Agent"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override public String description() {
        var sb = new StringBuilder();
        sb.append("Launch a sub-agent to handle a focused, isolated task. The sub-agent runs ");
        sb.append("independently with its own context and cannot see the current conversation ");
        sb.append("(unless forked).\n\nAvailable agent types:");
        for (String name : AgentLoader.listNames(agentSpecs)) {
            SubAgentSpec spec = agentSpecs.get(name);
            sb.append("\n- ").append(name).append(": ").append(spec.description());
        }
        sb.append("\n\nWrite a detailed prompt -- the sub-agent has no prior context. To inherit ");
        sb.append("this conversation's context, omit subagent_type (forks the parent).\n");
        sb.append("To spawn a long-lived TEAM MEMBER instead, set 'team_name' -- the member joins that team, ");
        sb.append("gets SendMessage + shared task-board tools, and stays resident for follow-up direction.");
        return sb.toString();
    }

    @Override public Map<String, Object> schema() {
        List<String> agentTypes = AgentLoader.listNames(agentSpecs);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of(
                "type", "string",
                "description", "A short (3-5 word) description of the task"));
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "The task for the agent to perform. Be detailed -- the agent has no context from this conversation."));
        properties.put("subagent_type", Map.of(
                "type", "string",
                "enum", agentTypes,
                "description", "The type of agent to use. Omit to fork the parent conversation (inherit history + tools)."));
        properties.put("model", Map.of(
                "type", "string",
                "enum", List.of("sonnet", "opus", "haiku"),
                "description", "Override the model for this agent. Defaults to the parent's model."));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "Set to true to run the agent in the background and get a task id."));
        properties.put("timeout_ms", Map.of(
                "type", "integer",
                "description", "Foreground timeout in milliseconds; on timeout the agent is moved to the background."));
        properties.put("team_name", Map.of(
                "type", "string",
                "description", "If set, spawn a long-lived TEAM MEMBER in this team (joins team, SendMessage + shared task board tools, stays resident)."));
        properties.put("name", Map.of(
                "type", "string",
                "description", "Optional member name for the teammate (defaults to a slug of the description)."));
        properties.put("plan_mode_required", Map.of(
                "type", "boolean",
                "description", "For teammates: require plan approval by the lead before the member may modify files."));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("description", "prompt"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", inputSchema);
        return schema;
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        String description = str(args, "description");
        String prompt = str(args, "prompt");
        if (description == null || description.isEmpty() || prompt == null || prompt.isEmpty()) {
            return ToolResult.error("Error: description and prompt are required");
        }

        String subagentType = str(args, "subagent_type");
        String modelOverride = str(args, "model");
        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));
        long timeoutSeconds = longArg(args, "timeout_ms") / 1000L;

        // 第 15 章：team_name → 长期队员（共享任务板 + 邮箱协作，干完空闲待命）。
        String teamName = str(args, "team_name");
        if (teamName != null && !teamName.isEmpty()) {
            if (teamManager == null) {
                return ToolResult.error("Error: team_name specified but no team manager is configured");
            }
            return runAsTeammate(teamName, str(args, "name"), description, prompt, subagentType,
                    modelOverride, Boolean.TRUE.equals(args.get("plan_mode_required")));
        }

        // Fork 关闭时，省略 subagent_type 不再 fork，回退到通用 agent。
        if ((subagentType == null || subagentType.isEmpty()) && forkDisabled) {
            subagentType = GENERAL_PURPOSE_AGENT_TYPE;
        }

        // Fork 路径：无 subagent_type。
        if (subagentType == null || subagentType.isEmpty()) {
            return runFork(description, prompt, modelOverride);
        }

        SubAgentSpec spec = resolveSpec(subagentType);
        if (spec == null) {
            return ToolResult.error("Error: unknown agent type '%s'. Available: %s".formatted(
                    subagentType, String.join(", ", AgentLoader.listNames(agentSpecs))));
        }

        return runInBackground
                ? runAsync(spec, description, prompt, modelOverride, timeoutSeconds)
                : runSync(spec, description, prompt, modelOverride, timeoutSeconds);
    }

    // ── 定义式：同步 ─────────────────────────────────────────────────────

    private ToolResult runSync(SubAgentSpec spec, String description, String prompt,
                               String modelOverride, long timeoutSeconds) {
        com.novacode.protocol.LlmClient subClient = runner.selectClient(spec.model(), modelOverride);
        PermissionMode mode = spec.permissionMode() != null ? spec.permissionMode() : parentModeSupplier.get();

        WorktreeService.Enter enter = enterWorktree(spec);
        boolean cleanup = true;
        try {
            Path cwd = enter == null ? null : enter.cwd();
            ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec, false, cwd);
            var agent = runner.buildAgent(subRegistry, subClient, mode, spec.maxTurns(), cwd);
            List<ChatMessage> history = new ArrayList<>();
            history.add(new ChatMessage(ChatMessage.Role.USER, prompt));

            SubAgentRunner.RunResult rr = runner.run(agent, history, effectiveSystemPrompt(spec, enter),
                    mode == PermissionMode.PLAN, description, spec.name(), timeoutSeconds, true);

            return switch (rr) {
                case SubAgentRunner.RunResult.Done d -> {
                    SubAgentRunner.Outcome o = d.outcome();
                    yield o.ok()
                            ? ToolResult.success("Agent \"%s\" completed in %.1fs.\n\n%s".formatted(
                                    description, o.seconds(), o.output()))
                            : ToolResult.error("Agent \"%s\" failed: %s".formatted(
                                    description, o.error() == null ? "unknown error" : o.error()));
                }
                case SubAgentRunner.RunResult.Detached det -> {
                    // 超时转后台续跑：worktree 留给后台，由 StaleCleanup 后续回收（有改动则不误删）
                    cleanup = false;
                    if (taskManager == null) {
                        yield ToolResult.error("Agent \"%s\" timed out but no task manager is configured".formatted(description));
                    }
                    String taskId = taskManager.continueInBackground(
                            spec.name() + ": " + truncate(prompt, 50), det.continuation());
                    yield ToolResult.success("Agent \"%s\" is taking long; moved to background (task %s). "
                            + "You will be notified when it completes.".formatted(description, taskId));
                }
            };
        } finally {
            if (cleanup) exitWorktree(enter);
        }
    }

    // ── 定义式：后台 ─────────────────────────────────────────────────────

    private ToolResult runAsync(SubAgentSpec spec, String description, String prompt,
                                String modelOverride, long timeoutSeconds) {
        if (taskManager == null) {
            return ToolResult.error("Background execution not available (no task manager configured)");
        }
        com.novacode.protocol.LlmClient subClient = runner.selectClient(spec.model(), modelOverride);
        PermissionMode mode = spec.permissionMode() != null ? spec.permissionMode() : parentModeSupplier.get();
        boolean planMode = mode == PermissionMode.PLAN;
        int maxTurns = spec.maxTurns();

        String taskId = taskManager.spawnBackground(spec.name() + ": " + truncate(prompt, 50), () -> {
            WorktreeService.Enter enter = enterWorktree(spec);
            try {
                Path cwd = enter == null ? null : enter.cwd();
                // 后台白名单在 worker 内应用，避免前台线程做额外过滤（也隔离实例）。
                ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec, true, cwd);
                var agent = runner.buildAgent(subRegistry, subClient, mode, maxTurns, cwd);
                List<ChatMessage> history = new ArrayList<>();
                history.add(new ChatMessage(ChatMessage.Role.USER, prompt));
                SubAgentRunner.RunResult rr = runner.run(agent, history, effectiveSystemPrompt(spec, enter),
                        planMode, description, spec.name(), 0, false); // 超时由 taskManager 看门狗负责
                return rr.outcome();
            } finally {
                exitWorktree(enter);
            }
        }, timeoutSeconds);

        return ToolResult.success("Agent \"%s\" launched in background (task %s). "
                + "You will be notified when it completes.".formatted(description, taskId));
    }

    // ── Fork 式 ──────────────────────────────────────────────────────────

    private ToolResult runFork(String description, String prompt, String modelOverride) {
        // 主检测：querySource 标记（压缩安全，历史被摘要后仍可检测）。
        if (FORK_QUERY_SOURCE.equals(querySource)) {
            return ToolResult.error("Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.");
        }
        List<ChatMessage> parentHistory = parentHistorySupplier == null ? null : parentHistorySupplier.get();
        if (parentHistory == null) {
            return ToolResult.error("Error: fork requires parent conversation context");
        }
        if (taskManager == null) {
            return ToolResult.error("Error: fork requires a task manager (fork always runs in background)");
        }
        // 双保险：历史里出现过 fork 引导标签也拦截（防 querySource 标记被绕过）。
        for (ChatMessage m : parentHistory) {
            if (m.getContent() != null && m.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return ToolResult.error("Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.");
            }
        }

        com.novacode.protocol.LlmClient subClient = runner.selectClient(null, modelOverride);
        PermissionMode mode = parentModeSupplier.get();
        ToolRegistry forkedRegistry = ToolFilter.cloneForFork(parentRegistry);
        List<ChatMessage> forkedHistory = buildForkedHistory(parentHistory, prompt);
        String sysPrompt = parentSystemPromptSupplier == null ? null : parentSystemPromptSupplier.get();
        boolean planMode = mode == PermissionMode.PLAN;

        String taskId = taskManager.spawnBackground("fork: " + truncate(prompt, 50), () -> {
            var agent = runner.buildAgent(forkedRegistry, subClient, mode, 0); // 无 maxTurns 限制
            SubAgentRunner.RunResult rr = runner.run(agent, forkedHistory, sysPrompt, planMode,
                    description, "fork", 0, false);
            return rr.outcome();
        }, 0); // fork 不设看门狗，跑到自然结束

        return ToolResult.success("Forked agent \"%s\" launched in background (task %s). "
                + "Results will arrive via task-notification.".formatted(description, taskId));
    }

    // ── 第 15 章：长期队员 ───────────────────────────────────────────────

    /** 派长期队员：建/取团队 → 注册成员 → 按团队后端派生（进程内/窗格）。 */
    private ToolResult runAsTeammate(String teamName, String memberName, String description, String prompt,
                                     String subagentType, String modelOverride, boolean planModeRequired) {
        try {
            TeamManager.Team team = teamManager.getOrCreateTeam(teamName, TeamManager.detectBackend(),
                    TeamManager.LEAD_NAME, "auto team");
            String baseName = memberName != null && !memberName.isBlank()
                    ? SpawnDispatcher.slugify(memberName)
                    : SpawnDispatcher.slugify(description);
            com.novacode.protocol.LlmClient client = runner.selectClient(null, modelOverride);
            PermissionMode mode = PermissionMode.DEFAULT;
            // 队员工作目录：可后续从 worktree 隔离中获得；本步先共享主工作目录（沙箱不设）。
            SpawnDispatcher.SpawnConfig cfg = new SpawnDispatcher.SpawnConfig(teamName, baseName, prompt,
                    teammateBasePrompt(subagentType), modelOverride, null, mode, 0,
                    planModeRequired, team.leadInbox());
            String status = SpawnDispatcher.spawnTeammate(teamManager, runner, parentRegistry, client, cfg);
            return ToolResult.success(status);
        } catch (Exception e) {
            return ToolResult.error("Error spawning teammate: " + e.getMessage());
        }
    }

    /** 队员的基础系统提示：角色正文优先，否则 null（TeammateRunner 会补成员附加段）。 */
    private String teammateBasePrompt(String subagentType) {
        if (subagentType != null && !subagentType.isEmpty()) {
            SubAgentSpec s = resolveSpec(subagentType);
            if (s != null && s.systemPrompt() != null && !s.systemPrompt().isBlank()) {
                return s.systemPrompt();
            }
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private SubAgentSpec resolveSpec(String name) {
        if (agentSpecs != null) {
            SubAgentSpec s = agentSpecs.get(name);
            if (s != null) return s;
        }
        return switch (name) {
            case GENERAL_PURPOSE_AGENT_TYPE -> SubAgentSpec.GENERAL_PURPOSE;
            case "plan" -> SubAgentSpec.PLAN;
            case "explore" -> SubAgentSpec.EXPLORE;
            default -> null;
        };
    }

    /** 子 Agent system prompt：角色正文优先，否则继承父 system prompt。 */
    private String effectiveSystemPrompt(SubAgentSpec spec) {
        if (spec.systemPrompt() != null && !spec.systemPrompt().isBlank()) return spec.systemPrompt();
        if (parentSystemPromptSupplier != null) {
            String p = parentSystemPromptSupplier.get();
            if (p != null && !p.isBlank()) return p;
        }
        return null;
    }

    /** 子 Agent system prompt + worktree 路径通知（enter 非空时追加）。 */
    private String effectiveSystemPrompt(SubAgentSpec spec, WorktreeService.Enter enter) {
        String base = effectiveSystemPrompt(spec);
        if (enter == null) return base;
        String notice = enter.notice();
        return (base == null || base.isBlank()) ? notice : base + "\n\n" + notice;
    }

    /** 隔离角色且服务可用时，创建（或 fast-resume）worktree 并返回 enter；否则 null。 */
    private WorktreeService.Enter enterWorktree(SubAgentSpec spec) {
        if (worktreeService == null) return null;
        if (spec.isolation() != Isolation.WORKTREE) return null;
        return worktreeService.enter(worktreeService.newEphemeralName());
    }

    /** 退出 worktree：有改动保留、无改动删除。 */
    private void exitWorktree(WorktreeService.Enter enter) {
        if (enter != null) worktreeService.exit(enter);
    }

    /** 复制父历史 + fork 引导词；末尾若为悬空的工具调用消息（当前 in-flight），剥成纯文本。 */
    private static List<ChatMessage> buildForkedHistory(List<ChatMessage> parent, String task) {
        List<ChatMessage> forked = new ArrayList<>();
        for (int i = 0; i < parent.size(); i++) {
            ChatMessage m = parent.get(i);
            boolean isLast = i == parent.size() - 1;
            if (m.hasToolCalls() && isLast) {
                String text = m.getContent() == null || m.getContent().isEmpty()
                        ? "(tool call in progress)" : m.getContent();
                forked.add(new ChatMessage(ChatMessage.Role.ASSISTANT, text));
            } else {
                forked.add(m);
            }
        }
        forked.add(new ChatMessage(ChatMessage.Role.USER, FORK_BOILERPLATE + "\n\nYour task:\n" + task));
        return forked;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static long longArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {} }
        return 0L;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
