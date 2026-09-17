package com.novacode.ui;

import com.novacode.agent.Agent;
import com.novacode.agent.AgentEvent;
import com.novacode.command.BuiltinCommands;
import com.novacode.command.CommandContext;
import com.novacode.command.CommandParser;
import com.novacode.command.CommandRegistry;
import com.novacode.config.ConfigLoader;
import com.novacode.config.ProviderConfig;
import com.novacode.context.ContextManager;
import com.novacode.hook.HookContext;
import com.novacode.hook.HookEngine;
import com.novacode.hook.HookEvent;
import com.novacode.hook.HookLoader;
import com.novacode.memory.InstructionLoader;
import com.novacode.memory.MemoryManager;
import com.novacode.session.SessionStore;
import com.novacode.skill.Skill;
import com.novacode.skill.SkillMode;
import com.novacode.skill.SkillManager;
import com.novacode.mcp.McpConfigLoader;
import com.novacode.mcp.McpManager;
import com.novacode.model.ChatMessage;
import com.novacode.subagent.AgentLoader;
import com.novacode.subagent.AgentTool;
import com.novacode.subagent.SubAgentRunner;
import com.novacode.subagent.SubAgentSpec;
import com.novacode.subagent.SubAgentTaskManager;
import com.novacode.permission.*;
import com.novacode.prompt.PromptBuilder;
import com.novacode.prompt.PromptBuilder.BuildOptions;
import com.novacode.prompt.PromptBuilder.EnvironmentContext;
import com.novacode.protocol.LlmClient;
import com.novacode.teams.Coordinator;
import com.novacode.teams.TaskStopTool;
import com.novacode.teams.TeamManager;
import com.novacode.teams.TeamTaskTools;
import com.novacode.teams.TeamTools;
import com.novacode.teams.TeammateRunner;
import com.novacode.tool.ToolRegistry;
import com.novacode.tool.FileStateCache;
import com.novacode.tui.tea.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * Chat TUI model — Chapter 6: Agent Loop + five-layer permission system.
 *
 * Layout (top to bottom):
 *   Banner → Messages (with viewport) → Streaming / Tool status → Separator
 *   → Status bar: [mode] provider | ↑tok ↓tok model
 *   → ❯ input
 *
 * Permission (spec F6-F8): Shift+Tab cycles the mode
 * (DEFAULT→ACCEPT_EDITS→PLAN→BYPASS_PERMISSIONS→DEFAULT), /plan and /do switch
 * directly. The mode lives in {@link #currentMode} and is read by the engine on
 * every decision, so it persists across turns (F7). When a call lands on Ask,
 * the UI renders a confirmation block and resolves the future with 1/2/3 or
 * Enter / ↑↓ / Esc / Ctrl+C; ALLOW_FOREVER persists a rule to the local config.
 */
public class ChatModel implements Model, PermissionPrompter, CommandContext {

    private static final String VERSION = "1.0.0";

    /** 会话恢复时「隔太久」的判定阈值（秒）：距上次活动超过 1 小时则插时间跨度提醒。 */
    private static final long TIME_GAP_SECONDS = 3600;

    // ── State ───────────────────────────────────────────────────────────
    private ProviderConfig config;
    /** config.yaml 路径（/model 运行时切换用）。 */
    private final Path configPath;
    /** 第 15 章 F7：coordinator 能力开关（config 层，与 env 双锁判定见 {@link com.novacode.teams.Coordinator}）。 */
    private final boolean coordinatorEnabled;
    /** 第 15 章：Lead 侧团队管理器（长驻；关闭钩子 {@code teamManager::closeAll} 兜底停成员）。 */
    private final TeamManager teamManager;
    /** 组装式 system prompt；每轮重建（第 11 章：注入技能菜单与激活正文，见 {@link #buildCurrentSystemPrompt()}）。 */
    private String systemPrompt;
    private String instructions;
    private String memorySection;
    private LlmClient client;
    private EnvironmentContext env;
    private final ToolRegistry toolRegistry;
    private final McpManager mcpManager;
    private final FileStateCache fileStateCache = new FileStateCache();
    private final ContextManager contextManager;
    private final SessionStore sessionStore;
    private final MemoryManager memoryManager;
    private final CommandRegistry commands;
    private final Agent agent;
    /** 第 12 章：Hook 引擎（生命周期钩子，附加到 Agent）。 */
    private final HookEngine hookEngine = new HookEngine();
    /** session_start 注入的提示词，拼进每轮 system prompt。 */
    private volatile String sessionHookPrompts;
    private final SkillManager skillManager;
    /** 第 13 章：后台子 Agent 任务管理器，追踪状态/结果/用量并累积完成通知。 */
    private final SubAgentTaskManager subAgentTaskManager = new SubAgentTaskManager();
    private final List<Msg> msgs = new ArrayList<>();

    /**
     * 跨轮权威会话历史（含工具调用与结果）。Agent 循环直接在这份列表上追加
     * assistant/tool 消息，完成后即为下一轮的基础 —— 修复「跨轮工具上下文丢失」。
     * UI 的 {@code msgs} 仅用于渲染，两者独立。
     */
    private final List<ChatMessage> history = new ArrayList<>();

    private final StringBuilder inputBuf = new StringBuilder();
    private int inputCursor;
    private boolean streaming;
    private int scrollOffset;

    // 输入历史：index 0 = 最近一条；historyPos >= 0 表示当前输入框内容来自历史
    private final List<String> inputHistory = new ArrayList<>();
    private int historyPos = -1;
    private static final int INPUT_HISTORY_MAX = 50;

    /** 记录一条已提交输入（去重相邻重复，超量裁剪）。 */
    private void rememberInput(String text) {
        if (text == null || text.isBlank()) return;
        inputHistory.remove(text);
        inputHistory.add(0, text);
        while (inputHistory.size() > INPUT_HISTORY_MAX) inputHistory.remove(inputHistory.size() - 1);
        historyPos = -1;
    }

    /** ↑(+1)/↓(-1) 在输入历史间移动；回到 -1 以下清空输入框（回到"新输入"状态）。 */
    private void recallHistory(int delta) {
        int next = historyPos + delta;
        if (next >= inputHistory.size()) return;
        inputBuf.setLength(0);
        if (next < 0) {
            historyPos = -1;
        } else {
            historyPos = next;
            inputBuf.append(inputHistory.get(next));
        }
        inputCursor = inputBuf.length();
    }

    // Agent-loop state
    private volatile BlockingQueue<AgentEvent> agentQueue;
    private final StringBuilder streamBuf = new StringBuilder();
    private long streamStartMs;
    private int currentTurn;

    // ── 第 6 章权限系统 ───────────────────────────────────────────────
    /** 当前权限模式：Shift+Tab 循环、/plan、/do；引擎每次判定读取（跨轮保持，F7）。 */
    private PermissionMode currentMode;
    /** 人在回路待确认状态：agent 线程阻塞于 future，UI 线程按键回填。 */
    private CompletableFuture<HitlOutcome> pendingAskFuture;
    private AskContext pendingAsk;
    private int askSelection;
    private int totalInputTokens, totalOutputTokens;
    private int totalCacheRead, totalCacheWrite;
    private final List<ToolRun> runningTools = new ArrayList<>();

    /** 命令分发后的收尾标志（第 10 章）：/exit 请求退出、PROMPT 类命令请求启动 Agent。 */
    private boolean quitRequested;
    private boolean promptPending;

    private int termW = 80, termH = 24;

    @SuppressWarnings("this-escape") // 构造器把 this 作为 PermissionPrompter 传入引擎；引擎仅持有引用，构造完成后才调用
    public ChatModel(ProviderConfig config) {
        this(config, false);
    }

    @SuppressWarnings("this-escape") // 同单参构造器
    public ChatModel(ProviderConfig config, boolean coordinatorEnabled) {
        this(config, coordinatorEnabled, Path.of("config.yaml"));
    }

    @SuppressWarnings("this-escape") // 同单参构造器
    public ChatModel(ProviderConfig config, boolean coordinatorEnabled, Path configPath) {
        this.config = config;
        this.coordinatorEnabled = coordinatorEnabled;
        this.configPath = configPath;
        this.teamManager = new TeamManager();
        Path projectRoot = Path.of(System.getProperty("user.dir"));

        // 第 9 章：会话存档 + 自动笔记。先建组件、清理过期会话。
        this.sessionStore = new SessionStore(projectRoot);
        sessionStore.cleanExpired();
        this.memoryManager = new MemoryManager(projectRoot, config);

        // 组装式 system prompt（第 5 章）：稳定模块前缀 + 环境块 + 预留可选槽。
        // 第 9 章把项目指令文件填入 customInstructions 槽、自动记忆索引填入 memorySection 槽，
        // 第 11 章把技能菜单/激活正文填入 skillSection 槽（每轮重建），
        // 三者都在稳定前缀之后追加，不破坏缓存命中（F3/F9）。
        this.env = PromptBuilder.detectEnvironment(config.getModel());
        this.instructions = InstructionLoader.loadInstructions(projectRoot);
        this.memorySection = memoryManager.buildMemorySection();
        this.client = LlmClient.create(config, null);
        this.toolRegistry = ToolRegistry.createDefault();

        // 第 7 章：MCP 客户端。启动时自动发现并注册外部 MCP Server 的工具，
        // 逐个容错 —— 单个 Server 连不上只报错、不影响其余（F8）。
        this.mcpManager = new McpManager();
        Map<String, String> mcpErrors = mcpManager.connectAll(McpConfigLoader.load());
        mcpManager.registerTools(toolRegistry);
        for (var e : mcpErrors.entrySet()) {
            System.err.println("[MCP] failed to connect server '" + e.getKey() + "': " + e.getValue());
        }

        // 第 6 章：五层权限系统。加载三级配置，接入 Agent 编排层（与 provider 无关）。
        PermissionConfig permConfig = RuleLoader.load();
        this.currentMode = permConfig.defaultMode();
        Path localRulesPath = Path.of(System.getProperty("user.dir"),
                ".novacode", "permissions.local.yaml");
        var permissionEngine = new PermissionEngine(
                new Blacklist(),
                new PathSandbox(Path.of(System.getProperty("user.dir"))),
                permConfig.engine(),
                () -> currentMode,
                this,
                localRulesPath);
        // 第 8 章：上下文管理。注入 Agent 循环，每次请求前预防存盘 + 兜底压缩。
        this.contextManager = new ContextManager(config);
        this.agent = new Agent(client, toolRegistry, config.getProtocol(), permissionEngine, contextManager);
        // 第 9 章：自然结束（无工具调用）后异步沉淀记忆（F7/N1）。
        agent.setOnNaturalStop(memoryManager::extractAsync);

        // 第 12 章：Hook 系统。加载两级配置 + 集中校验，附加到 Agent（缺失即空）。
        agent.setHookEngine(hookEngine);
        var hookLoaded = HookLoader.load(projectRoot, Path.of(System.getProperty("user.home")));
        hookEngine.loadHooks(hookLoaded.hooks());
        for (String err : hookLoaded.errors()) {
            System.err.println("[Hook] " + err);
        }

        // Wire FileStateCache into tools that need it
        for (var tool : toolRegistry.listTools()) {
            if (tool instanceof com.novacode.tool.impl.ReadFileTool r)
                r.setFileStateCache(fileStateCache);
            else if (tool instanceof com.novacode.tool.impl.WriteFileTool w)
                w.setFileStateCache(fileStateCache);
            else if (tool instanceof com.novacode.tool.impl.EditFileTool e)
                e.setFileStateCache(fileStateCache);
        }

        // 第 9 章：恢复最近会话（自动续上中断前的工作记忆）。
        resumeSession();

        // 第 12 章：session_start 事件 + 提示注入（注入正文拼进每轮 system prompt）。
        List<String> injected = hookEngine.runInjectHooks(
                new HookContext(HookEvent.SESSION_START, null, null, null, null, null));
        if (!injected.isEmpty()) {
            this.sessionHookPrompts = String.join("\n\n", injected);
        }

        // 第 10 章：命令注册中心。启动期注册全部内置命令并检测别名冲突（冲突即抛异常退出）。
        this.commands = BuiltinCommands.build(contextManager, sessionStore,
                memoryManager, permConfig, config);

        // 第 11 章：Skill 编排器。扫描三级目录、校验白名单、注册 use_skill；
        // 再注册技能斜杠命令，最后构建含技能菜单的初始 system prompt。
        this.skillManager = new SkillManager(toolRegistry, agent, client, config,
                projectRoot, () -> systemPrompt, () -> history);
        skillManager.registerCommands(commands);

        // 第 13 章：子 Agent 委派。加载角色定义，建共享 runner + 统一 Agent 工具（定义式 / Fork 式）。
        Map<String, SubAgentSpec> agentSpecs = AgentLoader.loadAll(projectRoot);
        SubAgentRunner subRunner = new SubAgentRunner(client, config.getProtocol(), config, permissionEngine);
        subRunner.setModelResolver(alias -> {
            ProviderConfig cloned = new ProviderConfig(config.getName(), config.getProtocol(),
                    config.getApiKey(), alias, config.getBaseUrl(), config.isThinking());
            cloned.setContextWindow(config.getContextWindow());
            return LlmClient.create(cloned, null);
        });
        AgentTool agentTool = new AgentTool(toolRegistry, subRunner);
        agentTool.setAgentSpecs(agentSpecs);
        agentTool.setTaskManager(subAgentTaskManager);
        agentTool.setParentModeSupplier(() -> currentMode);
        agentTool.setParentHistorySupplier(() -> history);
        agentTool.setParentSystemPromptSupplier(this::buildCurrentSystemPrompt);
        agentTool.setWorktreeService(new com.novacode.worktree.WorktreeService(projectRoot));
        toolRegistry.register(agentTool);

        // 第 15 章：Lead 侧团队工具注册 + 队员委派接线 + coordinator 模式。
        // 这些工具只给主 Agent（Lead）；普通子 Agent / fork 已被 ToolFilter 挡掉，
        // 队员仅通过 buildTeammateRegistry 拿到 SendMessage + Task 四件套。
        agentTool.setTeamManager(teamManager);
        toolRegistry.register(new TeamTools.SendMessageTool(teamManager, TeamManager.LEAD_NAME));
        toolRegistry.register(new TeamTools.TeamCreateTool(teamManager, TeamManager.LEAD_NAME));
        toolRegistry.register(new TeamTools.TeamDeleteTool(teamManager));
        toolRegistry.register(new TeamTaskTools.TaskCreateTool(teamManager, null));
        toolRegistry.register(new TeamTaskTools.TaskGetTool(teamManager, null));
        toolRegistry.register(new TeamTaskTools.TaskListTool(teamManager, null));
        toolRegistry.register(new TeamTaskTools.TaskUpdateTool(teamManager, null));
        toolRegistry.register(new TaskStopTool(teamManager));
        // F7：coordinator 双锁激活时，每轮收窄工具白名单为调度只读集 + 注入调度指引；
        // 未激活（config 或 env 任一关）则全工具照常。运行时随 env 变化自适应。
        agent.setToolNameFilter(name ->
                Coordinator.active(coordinatorEnabled) ? Coordinator.isCoordinatorTool(name) : true);
        agent.setCoordinatorActiveFn(() -> Coordinator.active(coordinatorEnabled));
        // 进程退出兜底：停所有队员虚拟线程并释放锁文件。
        Runtime.getRuntime().addShutdownHook(new Thread(teamManager::closeAll));

        this.systemPrompt = buildCurrentSystemPrompt();
    }

    /** 每轮重建 system prompt：稳定前缀 + 环境块 + 技能段（菜单 + 激活正文）+ 自定义指令 + 记忆 + Hook 注入。 */
    private String buildCurrentSystemPrompt() {
        String base = PromptBuilder.buildSystemPrompt(env,
                new BuildOptions(skillManager.buildSkillSection(), instructions, memorySection));
        String injected = sessionHookPrompts;
        if (injected != null && !injected.isEmpty()) {
            base = base + "\n\n" + injected;
        }
        return base;
    }

    /** /model <名称|序号>：空闲时切换 provider（client/窗口阈值/摘要/环境块联动重建）。 */
    @Override
    public String switchModel(String selector) {
        if (streaming) return "任务进行中，等当前轮结束后再切换";
        List<ProviderConfig> providers;
        try {
            providers = ConfigLoader.load(configPath);
        } catch (Exception e) {
            return "读取配置失败: " + e.getMessage();
        }
        ProviderConfig target = com.novacode.App.resolveProvider(providers, selector);
        if (target == null) {
            var sb = new StringBuilder("未找到 provider '" + selector + "'，可用：");
            for (int i = 0; i < providers.size(); i++) {
                sb.append("\n  ").append(i + 1).append(". ").append(providers.get(i).getName());
            }
            return sb.toString();
        }
        if (target.getName().equals(config.getName())) {
            return "已在使用 " + target.getName() + "（" + config.getModel() + "）";
        }
        config = target;
        client = LlmClient.create(config, null);
        agent.setClient(client);
        contextManager.switchProvider(config);
        env = PromptBuilder.detectEnvironment(config.getModel());
        systemPrompt = buildCurrentSystemPrompt();
        return "已切换到 " + config.getName() + "（" + config.getModel() + "）";
    }

    /** /model：列出配置中的全部 provider，标记当前。 */
    @Override
    public String modelList() {
        try {
            List<ProviderConfig> providers = ConfigLoader.load(configPath);
            var sb = new StringBuilder("可用 provider（/model <名称|序号> 切换）：");
            for (int i = 0; i < providers.size(); i++) {
                ProviderConfig p = providers.get(i);
                sb.append("\n  ").append(i + 1).append(". ").append(p.getName())
                        .append(" (").append(p.getModel()).append(")");
                if (p.getName().equals(config.getName())) sb.append(" ← 当前");
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取配置失败: " + e.getMessage();
        }
    }

    /** 触发会话级/系统级事件（无注入返回，动作失败只记日志不抛出）。 */
    private void fireSessionHook(HookEvent event) {
        hookEngine.runHooks(new HookContext(event, null, null, null, null, null));
    }

    /** 启动时自动恢复最近会话：加载 + 时间跨度提醒 + 超限压一次（F6）。 */
    private void resumeSession() {
        var resume = sessionStore.resumeMostRecent();
        if (!resume.found()) {
            sessionStore.startNewSession();
            msgs.add(new Msg("tool", "── 新会话 " + sessionStore.currentId() + " ──", false));
            return;
        }

        history.addAll(resume.messages());
        if (resume.lastTimestamp() > 0
                && (System.currentTimeMillis() / 1000 - resume.lastTimestamp()) > TIME_GAP_SECONDS) {
            history.add(0, timeGapMessage(resume.lastTimestamp()));
        }

        // token 超限先压一次：接近窗口上限才真正压缩，否则 no-op。
        String compact = contextManager.compressNow(history);
        if (!compact.startsWith("无需压缩")) {
            // 历史被重建（摘要 + 边界 + 保留），与旧文件不再一一对应 → 开新会话落盘压缩后状态。
            sessionStore.startNewSession();
            sessionStore.sync(history);
            msgs.add(new Msg("tool", "── " + compact + " ──", false));
        }

        renderResume(resume.messages());
        msgs.add(new Msg("tool", "── 已恢复会话 " + resume.id() + "（"
                + resume.messages().size() + " 条消息）──", false));
    }

    /** 把恢复的对话渲染进 UI（仅 user/assistant 文本，工具与 system 不进显示）。 */
    private void renderResume(List<ChatMessage> loaded) {
        for (ChatMessage m : loaded) {
            switch (m.getRole()) {
                case USER -> {
                    if (m.getContent() != null && !m.getContent().isEmpty())
                        msgs.add(new Msg("user", m.getContent(), false));
                }
                case ASSISTANT -> {
                    if (m.getContent() != null && !m.getContent().isEmpty())
                        msgs.add(new Msg("assistant", m.getContent(), false));
                }
                default -> {}
            }
        }
    }

    /** 时间跨度提醒：距上次会话过久时提醒模型本会话是延续。 */
    private static ChatMessage timeGapMessage(long lastTs) {
        long gap = System.currentTimeMillis() / 1000 - lastTs;
        return new ChatMessage(ChatMessage.Role.USER,
                "<system-reminder>距上次会话已过 " + humanGap(gap)
                + "，本会话是上次会话的延续，请基于已有上下文继续。</system-reminder>");
    }

    private static String humanGap(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时";
        return (hours / 24) + " 天";
    }

    // ── Model interface ─────────────────────────────────────────────────

    @Override public Command init() { return Command.checkWindowSize(); }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        return switch (msg) {
            case KeyPressMessage kp -> handleKey(kp);
            case WindowSizeMessage ws -> { termW = ws.width(); termH = ws.height(); yield UpdateResult.from(this); }
            case QuitMessage q -> UpdateResult.from(this, QuitMessage::new);
            case StreamTick t -> pollAgent();
            default -> UpdateResult.from(this);
        };
    }

    @Override
    public String view() {
        var sb = new StringBuilder();
        int w = Math.max(40, termW);

        // Scrollable content area
        List<String> content = new ArrayList<>();
        content.addAll(renderBannerLines(w));
        content.add("");
        content.add(Styles.dim("  Ready. Type a message. (/help for commands, Ctrl+C to quit)"));
        content.add("");

        // Messages
        for (var m : msgs) {
            switch (m.role) {
                case "user" -> {
                    content.add(Styles.userText.render("── You ──"));
                    for (String line : sanitize(m.content).split("\n"))
                        content.add("  " + line);
                }
                case "assistant" -> {
                    if (m.error) {
                        content.add(Styles.error.render("── Error ──"));
                        content.add(Styles.error.render("  " + sanitize(m.content)));
                    } else {
                        content.add(Styles.aiMarker.render("── Nova ──"));
                        for (String line : com.novacode.ui.MarkdownView.renderLines(sanitize(m.content)))
                            content.add("  " + line);
                    }
                }
                case "tool" -> {
                    // 多行工具消息（任务清单等）逐行渲染，最多 6 行后折叠
                    String[] lines = sanitize(m.content).split("\n");
                    content.add(Styles.dim("  ● " + lines[0]));
                    for (int i = 1; i < lines.length && i < 6; i++)
                        content.add(Styles.dim("    " + lines[i]));
                    if (lines.length > 6) content.add(Styles.dim("    …"));
                }
                case "tool_result" -> {
                    content.add(Styles.dim("  └─ " + clipLines(sanitize(m.content), 3)));
                }
            }
            content.add("");
        }

        // Running tools (during streaming)
        if (streaming && !runningTools.isEmpty()) {
            for (var tr : runningTools) {
                String args = tr.argsPreview();
                content.add(Styles.yellow("  ● " + tr.toolName) + Styles.dim("(" + args + ") Running…"));
            }
            content.add("");
        }

        // Streaming text：尾部预览（跟随最新输出），已完成行过 Markdown 渲染，
        // 末行（可能未写完）保持原文，避免半截标记闪烁。
        if (streaming && !streamBuf.isEmpty()) {
            content.add(Styles.aiMarker.render("── Nova ──") + " " + Styles.yellow("● streaming"));
            String text = sanitize(streamBuf.toString());
            String[] lines = text.split("\n", -1);
            String completed = lines.length == 1 ? "" : text.substring(0, text.lastIndexOf('\n'));
            var rendered = new ArrayList<String>(MarkdownView.renderLines(completed));
            rendered.add(lines[lines.length - 1]);
            int preview = 8;
            for (int i = Math.max(0, rendered.size() - preview); i < rendered.size(); i++)
                content.add("  " + rendered.get(i));
            if (rendered.size() > preview) content.add(Styles.dim("  …"));
            content.add("");
        }

        // Dynamic area: thinking + iteration
        if (streaming && runningTools.isEmpty() && pendingAsk == null) {
            long elapsed = (System.currentTimeMillis() - streamStartMs) / 1000;
            String dynLine = Styles.dim("  Thinking… (" + elapsed + "s");
            if (currentTurn > 0) dynLine += " · 第 " + currentTurn + " 轮";
            dynLine += ")";
            content.add(dynLine);
            content.add("");
        }

        // HITL 权限确认块（spec F8）：agent 虚拟线程阻塞于 future.join()，
        // 这里的按键通过 handleAskKey → resolveAsk 回填。1/2/3 或 Enter 确认，
        // ↑↓ 切换选择，Esc/Ctrl+C 取消整个循环。
        if (pendingAsk != null) {
            AskContext ctx = pendingAsk;
            content.add(Styles.error.render("  ── 权限确认 ──"));
            content.add("  " + Styles.dim(ctx.toolName() + " 请求执行"));
            if (!ctx.argsPreview().isEmpty())
                content.add("  " + Styles.dim(ctx.argsPreview()));
            content.add("  " + Styles.dim("原因: " + ctx.reason()));
            String[] labels = { "允许一次", "永久允许（写入本地配置）", "拒绝" };
            for (int i = 0; i < labels.length; i++) {
                boolean sel = askSelection == i;
                content.add("   " + (sel ? Styles.yellow("● ") : "   ") + labels[i]);
            }
            content.add(Styles.dim("  ↑/↓ 选择 · Enter 确认 · Esc 取消"));
            content.add("");
        }

        // Fixed bottom: status + input (5 lines for running tools area;
        // +3 more while a permission confirmation block is active)
        int bottomH = streaming && !runningTools.isEmpty() ? 5 + runningTools.size() : 5;
        if (pendingAsk != null) bottomH += 3;
        int viewH = Math.max(termH - bottomH, 3);

        if (scrollOffset > content.size() - viewH)
            scrollOffset = Math.max(0, content.size() - viewH);
        int start = Math.max(0, content.size() - viewH - scrollOffset);
        int end = Math.min(content.size(), start + viewH);

        for (int i = start; i < end; i++)
            sb.append(content.get(i)).append("\n");
        for (int i = end - start; i < viewH; i++)
            sb.append("\n");

        // Separator
        sb.append(Styles.dim("─".repeat(w))).append("\n");

        // Status bar
        long elapsed = streaming ? (System.currentTimeMillis() - streamStartMs) / 1000 : -1;
        // F7/AC9：权限模式占据原 provider 名位置，状态栏不再显示 provider 名。
        String statusLeft = " " + Styles.yellow("[" + currentMode.label() + "]");
        String todoProgress = todoProgressSuffix();
        if (todoProgress != null) statusLeft += " " + Styles.green("✓" + todoProgress);
        String tokStr = "";
        if (totalInputTokens > 0 || totalOutputTokens > 0)
            tokStr = " ↑" + fmtTok(totalInputTokens) + " ↓" + fmtTok(totalOutputTokens)
                    + cacheRateSuffix() + " tok  ";
        String modelName = config.getModel();
        if (modelName.length() > 48) modelName = modelName.substring(0, 48) + "…";
        String statusRight;
        if (streaming && !streamBuf.isEmpty()) {
            statusRight = "Streaming… (" + elapsed + "s)  " + tokStr + modelName;
        } else if (streaming) {
            statusRight = "Thinking… (" + elapsed + "s)  " + tokStr + modelName;
        } else {
            statusRight = tokStr + modelName;
        }
        statusRight += " ";
        int mid = w - plainLen(statusLeft) - plainLen(statusRight);
        sb.append(Styles.statusBar.render(statusLeft + " ".repeat(Math.max(0, mid)) + statusRight)).append("\n");

        // Input
        if (streaming) {
            sb.append(Styles.prompt.render("❯ "));
            sb.append(Styles.dim("Waiting for response…"));
        } else if (inputBuf.isEmpty()) {
            sb.append(Styles.prompt.render("❯ "));
            sb.append(Styles.dim("Send a message..."));
        } else {
            String before = inputBuf.substring(0, inputCursor);
            char at = inputCursor < inputBuf.length() ? inputBuf.charAt(inputCursor) : ' ';
            String after = inputCursor < inputBuf.length() ? inputBuf.substring(inputCursor + 1) : "";
            sb.append(Styles.prompt.render("❯ "));
            sb.append(before).append("\033[7m").append(at).append("\033[0m").append(after);
        }

        return sb.toString();
    }

    // ── Keys ────────────────────────────────────────────────────────────

    private UpdateResult<ChatModel> handleKey(KeyPressMessage kp) {
        // 权限确认块激活时：所有按键先喂给确认块（1/2/3/Enter/↑↓/Esc/Ctrl+C），
        // 不进入输入框编辑 —— HITL 阻塞的 agent 线程通过 future 拿到结果（F8）。
        if (pendingAskFuture != null) return handleAskKey(kp);

        String key = kp.key();
        char[] runes = kp.runes();

        return switch (key) {
            case "ctrl+c" -> {
                if (streaming) {
                    // Cancel current Agent loop, don't exit
                    cancelAgentLoop();
                    msgs.add(new Msg("assistant", "（已取消）", false));
                    yield UpdateResult.from(this);
                }
                sessionStore.sync(history);
                yield UpdateResult.from(this, QuitMessage::new);
            }
            case "esc" -> {
                if (streaming) {
                    cancelAgentLoop();
                    msgs.add(new Msg("assistant", "（已取消）", false));
                    yield UpdateResult.from(this);
                }
                yield UpdateResult.from(this);
            }
            case "enter" -> submit();
            case "shift+tab" -> {
                if (streaming) yield UpdateResult.from(this);
                currentMode = currentMode.next();
                msgs.add(new Msg("tool", "── 权限模式 → " + currentMode.label() + " ──", false));
                yield UpdateResult.from(this);
            }
            case "tab" -> {
                // 第 10 章：Tab 补全命令名（唯一补全、多候选显示列表，F6）。
                String text = inputBuf.toString();
                if (text.startsWith("/") && text.indexOf(' ') < 0) {
                    List<String> cands = commands.complete(text.substring(1));
                    if (cands.size() == 1) {
                        inputBuf.setLength(0);
                        inputBuf.append("/").append(cands.get(0)).append(" ");
                        inputCursor = inputBuf.length();
                    } else if (cands.size() > 1) {
                        msgs.add(new Msg("tool", "── " + String.join("  ", cands) + " ──", false));
                    }
                }
                yield UpdateResult.from(this);
            }
            case "backspace", "ctrl+h" -> {
                if (inputCursor > 0) { inputBuf.deleteCharAt(inputCursor - 1); inputCursor--; }
                yield UpdateResult.from(this);
            }
            case "left" ->  { if (inputCursor > 0) inputCursor--; yield UpdateResult.from(this); }
            case "right" -> { if (inputCursor < inputBuf.length()) inputCursor++; yield UpdateResult.from(this); }
            case "home" ->  { inputCursor = 0; yield UpdateResult.from(this); }
            case "end" ->   { inputCursor = inputBuf.length(); yield UpdateResult.from(this); }
            case "up" -> {
                if (inputBuf.isEmpty() && !inputHistory.isEmpty()) {
                    recallHistory(+1);
                } else {
                    scrollOffset += 3;
                }
                yield UpdateResult.from(this);
            }
            case "down" -> {
                if (historyPos >= 0) {
                    recallHistory(-1);
                } else {
                    scrollOffset = Math.max(0, scrollOffset - 3);
                }
                yield UpdateResult.from(this);
            }
            case "pgup" ->   { scrollOffset += 3; yield UpdateResult.from(this); }
            case "pgdown" -> { scrollOffset = Math.max(0, scrollOffset - 3); yield UpdateResult.from(this); }
            default -> {
                if (runes != null) for (char ch : runes) if (ch >= 32) { inputBuf.insert(inputCursor, ch); inputCursor++; }
                yield UpdateResult.from(this);
            }
        };
    }

    // ── Submit ──────────────────────────────────────────────────────────

    // ── CommandContext（第 10 章）──────────────────────────────────────

    @Override public void display(String content) {
        msgs.add(new Msg("tool", content, false));
    }

    @Override public void clearMessages() {
        msgs.clear();
        skillManager.deactivateAll();
    }

    @Override public void sendPrompt(String content) {
        rememberInput(content);
        msgs.add(new Msg("user", content, false));
        history.add(new ChatMessage(ChatMessage.Role.USER, content));
        sessionStore.sync(history);
        promptPending = true;
    }

    /** 第 11 章：shared 激活并触发 Agent；independent 独立执行后回流摘要。 */
    @Override public void invokeSkill(String name, String args) {
        Skill s = skillManager.lookup(name);
        if (s == null) {
            msgs.add(new Msg("tool", "── 未知 Skill: " + name + "（用 /skills 查看）──", false));
            return;
        }
        if (s.mode() == SkillMode.INDEPENDENT) {
            // 同步跑完子对话，摘要回流主历史（不含子对话工具细节，F5/AC8）。
            String summary = skillManager.runIndependent(s, args);
            msgs.add(new Msg("assistant", summary, false));
            history.add(new ChatMessage(ChatMessage.Role.USER,
                    "<skill-result>\n" + summary + "\n</skill-result>"));
            sessionStore.sync(history);
        } else {
            String sop = skillManager.activate(name, args);
            msgs.add(new Msg("tool", "── 已激活 Skill: " + name + " ──", false));
            sendPrompt(sop);
        }
    }

    @Override public void setMode(PermissionMode mode) {
        currentMode = mode;
    }

    @Override public PermissionMode getMode() {
        return currentMode;
    }

    @Override public CommandContext.TokenStats tokens() {
        return new CommandContext.TokenStats(totalInputTokens, totalOutputTokens,
                totalCacheRead, totalCacheWrite,
                contextManager.estimateCurrent(history), config.getContextWindow());
    }

    @Override public String compactContext() {
        return contextManager.compressNow(history);
    }

    @Override public String newSession() {
        history.clear();
        msgs.clear();
        skillManager.deactivateAll();
        String id = sessionStore.startNewSession();
        // 第 12 章：新会话触发 session_start，重算注入提示。
        List<String> injected = hookEngine.runInjectHooks(
                new HookContext(HookEvent.SESSION_START, null, null, null, null, null));
        this.sessionHookPrompts = injected.isEmpty() ? null : String.join("\n\n", injected);
        return id;
    }

    /** /resume <id前缀>：当前会话先落盘，再切换到目标会话（历史 + 渲染重建）。 */
    @Override public String resumeSession(String idPrefix) {
        sessionStore.sync(history);
        var resume = sessionStore.resumeById(idPrefix);
        if (!resume.found()) {
            return "未找到匹配的会话（前缀 " + idPrefix + "），当前会话保持不变";
        }
        history.clear();
        history.addAll(resume.messages());
        if (resume.lastTimestamp() > 0
                && (System.currentTimeMillis() / 1000 - resume.lastTimestamp()) > TIME_GAP_SECONDS) {
            history.add(timeGapMessage(resume.lastTimestamp()));
        }
        // token 超限先压一次；压缩后消息序列与原文件不再一一对应 → 落新会话文件
        String compact = contextManager.compressNow(history);
        if (!compact.startsWith("无需压缩")) {
            sessionStore.startNewSession();
            sessionStore.sync(history);
        }
        msgs.clear();
        renderResume(resume.messages());
        String status = "已切换到会话 " + resume.id() + "（" + resume.messages().size() + " 条消息）";
        if (!compact.startsWith("无需压缩")) status += "；" + compact;
        return status;
    }

    @Override public void refreshStatus() {
        // TEA 每帧自动重绘，状态栏（含模式标记）无需额外刷新。
    }

    @Override public void quit() {
        sessionStore.sync(history);
        // 第 12 章：session_end + shutdown 事件（进程退出前最后一次触发）。
        fireSessionHook(HookEvent.SESSION_END);
        fireSessionHook(HookEvent.SHUTDOWN);
        quitRequested = true;
    }

    private UpdateResult<ChatModel> submit() {
        String text = inputBuf.toString().trim();
        if (text.isEmpty() || streaming) return UpdateResult.from(this);
        rememberInput(text);

        // 第 10 章：斜杠命令走本地分发，否则作为普通消息送 Agent（F5）。
        var parsed = CommandParser.parse(text);
        if (parsed.isPresent()) {
            CommandParser.Parsed p = parsed.get();
            var spec = commands.lookup(p.name());
            if (spec == null) {
                inputBuf.setLength(0); inputCursor = 0; scrollOffset = 0;
                msgs.add(new Msg("tool", "── 未知命令 /" + p.name() + "，用 /help 查看可用命令 ──", false));
                return UpdateResult.from(this);
            }
            quitRequested = false;
            promptPending = false;
            spec.handler().handle(this, p.args());
            if (quitRequested) {
                return UpdateResult.from(this, QuitMessage::new);
            }
            inputBuf.setLength(0); inputCursor = 0; scrollOffset = 0;
            if (promptPending) {
                return startAgent();
            }
            return UpdateResult.from(this);
        }

        // Normal message
        inputBuf.setLength(0); inputCursor = 0; scrollOffset = 0;
        msgs.add(new Msg("user", text, false));
        history.add(new ChatMessage(ChatMessage.Role.USER, text));
        sessionStore.sync(history);
        return startAgent();
    }

    private UpdateResult<ChatModel> startAgent() {
        // 跨轮会话历史由 history 持有（含工具调用与结果），submit 已把本轮用户消息加入；
        // 直接交给 Agent 循环，Agent 在本轮内追加 assistant/tool 消息到同一列表，
        // 完成后即构成下一轮的基础 —— 不再从 msgs 重建而丢失工具上下文。
        // 第 11 章：每轮重建 system prompt，钉住最新技能菜单与激活正文（F4）。
        systemPrompt = buildCurrentSystemPrompt();
        streaming = true; streamBuf.setLength(0); streamStartMs = System.currentTimeMillis();
        currentTurn = 0;
        // Ensure any previous loop is fully stopped before launching a new one.
        agent.interrupt();
        agent.joinCurrent(500);
        agentQueue = agent.run(history, systemPrompt, currentMode == PermissionMode.PLAN);
        return UpdateResult.from(this, Command.tick(Duration.ofMillis(50), now -> new StreamTick()));
    }

    // ── Agent Event Polling ─────────────────────────────────────────────

    private UpdateResult<ChatModel> pollAgent() {
        if (agentQueue == null || !streaming) return UpdateResult.from(this);
        while (true) {
            AgentEvent evt = agentQueue.poll();
            if (evt == null) break;
            switch (evt) {
                case AgentEvent.StreamText st -> streamBuf.append(st.text());
                case AgentEvent.ToolUseEvent tu -> {
                    // If there's preamble text, commit it first
                    if (!streamBuf.isEmpty()) {
                        msgs.add(new Msg("assistant", streamBuf.toString(), false));
                        streamBuf.setLength(0);
                    }
                    runningTools.add(new ToolRun(tu.toolId(), tu.toolName(), tu.args()));
                }
                case AgentEvent.ToolResultEvent tr -> {
                    runningTools.removeIf(t -> t.toolId.equals(tr.toolId()));
                    // 展示摘要：按行折叠到 3 行（TodoWrite 的清单输出本身有界，不折叠）
                    String resultSummary = tr.output();
                    if (!tr.isError() && !"TodoWrite".equals(tr.toolName())) {
                        resultSummary = clipLines(resultSummary, 3);
                        if (resultSummary.length() > 400)
                            resultSummary = resultSummary.substring(0, 400) + "…";
                    }
                    msgs.add(new Msg("tool", tr.toolName() + ": " + resultSummary, tr.isError()));
                }
                case AgentEvent.TurnComplete tc -> currentTurn = tc.turn();
                case AgentEvent.PermissionAskEvent pa -> {
                    // HITL：保存待确认状态与 future，下一帧 view() 渲染确认块；
                    // 用户在 handleAskKey 里按键，resolveAsk 回填 future 放行 agent 线程。
                    pendingAsk = pa.context();
                    pendingAskFuture = pa.future();
                    askSelection = 0;
                }
                case AgentEvent.UsageEvent ue -> {
                    totalInputTokens += ue.inputTokens();
                    totalOutputTokens += ue.outputTokens();
                    totalCacheRead += ue.cacheRead();
                    totalCacheWrite += ue.cacheWrite();
                }
                case AgentEvent.LoopComplete lc -> {
                    // Commit final text
                    if (!streamBuf.isEmpty()) {
                        msgs.add(new Msg("assistant", streamBuf.toString(), false));
                    }
                    streamBuf.setLength(0); streaming = false; agentQueue = null;
                    runningTools.clear(); currentTurn = 0;
                    sessionStore.sync(history);
                    drainSubAgentNotifications();
                    drainLeadMailbox();
                    writeCacheSmokeLog();
                    return UpdateResult.from(this);
                }
                case AgentEvent.ErrorEvent ee -> {
                    msgs.add(new Msg("assistant", ee.message(), true));
                    // Keep streaming=true so the Agent can continue or finish
                }
                case AgentEvent.Notice no -> {
                    msgs.add(new Msg("tool", no.message(), false));
                }
            }
        }
        return streaming
            ? UpdateResult.from(this, Command.tick(Duration.ofMillis(50), now -> new StreamTick()))
            : UpdateResult.from(this);
    }

    /** 第 13 章：每轮 LoopComplete 后排空后台子 Agent 完成通知，注入主历史供下一轮感知（不触发新轮）。 */
    private void drainSubAgentNotifications() {
        for (var n : subAgentTaskManager.drainNotifications()) {
            switch (n.status()) {
                case COMPLETED -> {
                    msgs.add(new Msg("tool", "── 后台子 Agent " + n.name() + " 完成 ──", false));
                    history.add(new ChatMessage(ChatMessage.Role.USER,
                            "<system-reminder>后台子 Agent 任务 " + n.name() + "（" + n.taskId()
                                    + "）已完成：\n" + n.output() + "\n</system-reminder>"));
                }
                case FAILED -> {
                    msgs.add(new Msg("tool", "── 后台子 Agent " + n.name() + " 失败 ──", true));
                    history.add(new ChatMessage(ChatMessage.Role.USER,
                            "<system-reminder>后台子 Agent 任务 " + n.name() + "（" + n.taskId()
                                    + "）失败：" + n.output() + "</system-reminder>"));
                }
                case CANCELLED -> {
                    msgs.add(new Msg("tool", "── 后台子 Agent " + n.name() + " 已取消 ──", false));
                    history.add(new ChatMessage(ChatMessage.Role.USER,
                            "<system-reminder>后台子 Agent 任务 " + n.name() + "（" + n.taskId()
                                    + "）已取消。</system-reminder>"));
                }
                default -> {}
            }
        }
    }

    /** 第 15 章 F6：每轮 LoopComplete 后排空 Lead 收件箱的未读消息（队员空闲/汇报/审批回复），
     *  折叠成 team-notification 注入主历史供下一轮感知（不触发新轮）。 */
    private void drainLeadMailbox() {
        for (ChatMessage m : TeammateRunner.drainLeadMailbox(teamManager, TeamManager.LEAD_NAME)) {
            history.add(m);
            msgs.add(new Msg("tool", "── 团队收件箱：新消息 ──", false));
        }
    }

    // ── HITL 确认块（spec F8）───────────────────────────────────────────

    /** PermissionPrompter 实现：在 agent 虚拟线程上被调用，把确认请求投递给
     *  UI 线程并阻塞，直到用户在确认块里按键回填结果。 */
    @Override
    public HitlOutcome ask(AskContext ctx) {
        var future = new CompletableFuture<HitlOutcome>();
        BlockingQueue<AgentEvent> q = agentQueue;
        if (q == null) return HitlOutcome.CANCELLED;
        try {
            q.put(new AgentEvent.PermissionAskEvent(ctx, future));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HitlOutcome.CANCELLED;
        }
        try {
            return future.get(); // interruptible — cancel can unblock via agent.interrupt()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HitlOutcome.CANCELLED;
        } catch (Exception e) {
            // 队列被清空（用户取消）或 future 异常 → 安全返回「取消」，
            // 上游 executeBatched 会以 completed=false 干净收尾，不泄漏虚拟线程。
            return HitlOutcome.CANCELLED;
        }
    }

    /** 确认块激活时的按键路由：1/2/3 或 Enter 确认，↑↓ 换选，Esc/Ctrl+C 取消。 */
    private UpdateResult<ChatModel> handleAskKey(KeyPressMessage kp) {
        String key = kp.key();
        return switch (key) {
            case "1", "enter" -> resolveAsk(HitlOutcome.ALLOW_ONCE);
            case "2" -> resolveAsk(HitlOutcome.ALLOW_FOREVER);
            case "3" -> resolveAsk(HitlOutcome.DENY);
            case "up" -> { askSelection = (askSelection + 2) % 3; yield UpdateResult.from(this); }
            case "down" -> { askSelection = (askSelection + 1) % 3; yield UpdateResult.from(this); }
            case "esc", "ctrl+c" -> resolveAsk(HitlOutcome.CANCELLED);
            default -> UpdateResult.from(this);
        };
    }

    private UpdateResult<ChatModel> resolveAsk(HitlOutcome outcome) {
        var future = pendingAskFuture;
        pendingAskFuture = null;
        pendingAsk = null;
        if (future != null) future.complete(outcome);
        return UpdateResult.from(this);
    }

    /** 取消整个 agent 循环：清空事件队列，并让仍在阻塞的 HITL 确认立即返回「取消」。
     *  覆盖两种时序——确认事件已入队未 poll（drain 队列补 future），
     *  以及已被 poll 设置到 pendingAskFuture（直接 complete）。 */
    private void cancelAgentLoop() {
        if (pendingAskFuture != null) {
            pendingAskFuture.complete(HitlOutcome.CANCELLED);
            pendingAskFuture = null;
            pendingAsk = null;
        }
        BlockingQueue<AgentEvent> q = agentQueue;
        if (q != null) {
            var drained = new java.util.ArrayList<AgentEvent>();
            q.drainTo(drained);
            for (var evt : drained) {
                if (evt instanceof AgentEvent.PermissionAskEvent pa) {
                    pa.future().complete(HitlOutcome.CANCELLED);
                }
            }
            q.clear();
        }
        agent.interrupt();       // unblock the agent thread if it's in poll()/sleep
        agent.joinCurrent(500);  // bounded wind-down so a new loop never races the old
        streaming = false;
        agentQueue = null;
        runningTools.clear();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<String> renderBannerLines(int w) {
        List<String> out = new ArrayList<>();
        String[] logo = {
            "✦   ✦   ✦✦✦   ✦   ✦   ✦✦✦",
            "✦✦  ✦  ✦   ✦  ✦   ✦  ✦   ✦",
            "✦ ✦ ✦  ✦   ✦  ✦   ✦  ✦✦✦✦",
            "✦  ✦✦  ✦   ✦   ✦ ✦   ✦   ✦",
            "✦   ✦   ✦✦✦     ✦    ✦   ✦",
        };
        String top = Styles.dim("╭" + "─".repeat(Math.max(0, w - 2)) + "╮");
        String bot = Styles.dim("╰" + "─".repeat(Math.max(0, w - 2)) + "╯");
        out.add(top);
        for (String line : logo) {
            int pad = (w - 2 - Program.displayWidth(line)) / 2;
            out.add(Styles.dim("│") + " ".repeat(Math.max(0, pad)) + Styles.banner.render(line) + " ".repeat(w - 2 - pad - Program.displayWidth(line)) + Styles.dim("│"));
        }
        out.add(Styles.dim("│ ") + Styles.cyan("NovaCode v" + VERSION) + " ".repeat(Math.max(0, w - 16 - VERSION.length())) + Styles.dim("│"));
        out.add(bot);
        return out;
    }

    private static String clipLines(String text, int max) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= max) return text;
        var sb = new StringBuilder();
        for (int i = 0; i < max; i++) sb.append(lines[i]).append("\n");
        return sb.append("…").toString();
    }

    /** Strip ANSI escape sequences and C0 control chars (except tab/newline) so
     *  model/user text can't inject terminal control codes (ANSI injection defense).
     *  包私有以便测试。 */
    static String sanitize(String s) {
        if (s == null) return "";
        if (s.isEmpty()) return s;
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                i++;
                if (i < s.length()) {
                    char intro = s.charAt(i);
                    if (intro == '[' || intro == ']' || intro == 'P' || intro == '^' || intro == '_') {
                        boolean stringType = intro != '[';
                        i++; // 从序列体开始扫（intro 本身在 CSI 终结符范围内，不能当终结符判）
                        while (i < s.length()) {
                            char d = s.charAt(i);
                            if (!stringType) {
                                if (d >= 0x40 && d <= 0x7E) break;
                            } else if (d == 0x07 || d == 0x1B) break;
                            i++;
                        }
                    }
                }
                continue;
            }
            if (c == '\t' || c == '\n') { sb.append(c); continue; }
            if (c < 0x20) continue; // drop C0 control chars (incl. \r)
            sb.append(c);
        }
        return sb.toString();
    }

    private static int plainLen(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "").length();
    }

    private static String fmtTok(int n) {
        if (n >= 1000) return String.format("%.1fk", n / 1000.0);
        return String.valueOf(n);
    }

    /** 任务清单进度（注册表里的 TodoWrite 实例）；无清单返回 null。 */
    private String todoProgressSuffix() {
        if (toolRegistry.get("TodoWrite") instanceof com.novacode.tool.impl.TodoTool t) {
            return t.progressSummary();
        }
        return null;
    }

    /** 缓存命中率后缀（状态栏 / status）。
     *  OpenAI-compat（DeepSeek 自动缓存）：prompt_tokens 已包含命中部分 → 命中/prompt 总量；
     *  Anthropic：input_tokens 不含缓存读写 → 命中/(输入+读+写)。无命中返回空串。 */
    private String cacheRateSuffix() {
        if (totalCacheRead <= 0) return "";
        long denom = "anthropic".equals(config.getProtocol())
                ? (long) totalInputTokens + totalCacheRead + totalCacheWrite
                : (long) totalInputTokens;
        if (denom <= 0) return "";
        int pct = (int) Math.round(100.0 * totalCacheRead / denom);
        return " ⚡" + pct + "%";
    }

    /**
     * 缓存命中率日志（默认关闭）：设 NOVACODE_CACHE_LOG=1 开启，每完成一轮追加一行到
     * nova_cache.log，用于验证 prompt caching 生效（第 2 轮起 cacheRead 应 > 0）。
     */
    private void writeCacheSmokeLog() {
        String flag = System.getenv("NOVACODE_CACHE_LOG");
        if (!"1".equals(flag) && !"true".equalsIgnoreCase(flag)) return;
        String line = "[nova_cache] " + LocalDateTime.now()
                + " model=" + config.getModel()
                + " cacheRead=" + totalCacheRead
                + " cacheWrite=" + totalCacheWrite + "\n";
        try {
            Files.writeString(Path.of("nova_cache.log"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // smoke 输出，失败静默
        }
    }

    private record Msg(String role, String content, boolean error) {}

    /** Lightweight tool-run tracker for the dynamic display area. */
    private static class ToolRun {
        final String toolId;
        final String toolName;
        final Map<String, Object> args;

        ToolRun(String toolId, String toolName, Map<String, Object> args) {
            this.toolId = toolId;
            this.toolName = toolName;
            this.args = args;
        }

        String argsPreview() {
            if ("TodoWrite".equals(toolName)) {
                if (args != null && args.get("todos") instanceof List<?> l) return l.size() + " 项任务";
                return "任务清单";
            }
            if (args == null || args.isEmpty()) return "";
            // Show first key=value pair
            var it = args.entrySet().iterator();
            var e = it.next();
            String val = String.valueOf(e.getValue());
            if (val.length() > 30) val = val.substring(0, 30) + "…";
            return e.getKey() + "=" + val;
        }
    }

    record StreamTick() implements Message {}
}
