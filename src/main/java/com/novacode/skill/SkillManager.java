package com.novacode.skill;

import com.novacode.agent.Agent;
import com.novacode.agent.AgentEvent;
import com.novacode.command.CommandRegistry;
import com.novacode.command.CommandSpec;
import com.novacode.command.CommandType;
import com.novacode.config.ProviderConfig;
import com.novacode.context.ContextManager;
import com.novacode.model.ChatMessage;
import com.novacode.permission.Blacklist;
import com.novacode.permission.PathSandbox;
import com.novacode.permission.PermissionEngine;
import com.novacode.permission.PermissionMode;
import com.novacode.permission.RuleEngine;
import com.novacode.prompt.PromptBuilder;
import com.novacode.protocol.LlmClient;
import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolRegistry;
import com.novacode.tool.ToolResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Skill 编排器（第 11 章）：持有「可用技能」与「激活技能」两个集合，
 * 负责两阶段加载（启动只注入菜单，按需加载正文）、工具白名单收窄、
 * 独立模式子对话、斜杠命令注册与热更新，并实现 {@code use_skill} 加载工具。
 *
 * <p>白名单语义（spec F6）：激活技能中只有「非空 whitelist」才参与收窄；
 * 收窄后的可见工具 = 各 whitelist 并集 ∪ 各专属工具名 ∪ {@code use_skill}。
 * 若没有任何非空 whitelist，则收窄解除、恢复全工具。</p>
 */
public final class SkillManager {

    public static final String USE_SKILL = "use_skill";

    private final ToolRegistry registry;
    private final Agent agent;
    private final LlmClient client;
    private final ProviderConfig config;
    private final Path projectRoot;
    private final Path userDir;
    private final Path projectDir;
    private final Supplier<String> systemPromptSupplier;
    private final Supplier<List<ChatMessage>> historySupplier;

    /** 可用技能（名字小写 → Skill），可被热更新重建。 */
    private Map<String, Skill> available;
    /** 激活技能（名字 → 已替换 {args} 的 SOP 正文）。 */
    private final Map<String, String> active = new LinkedHashMap<>();

    private CommandRegistry commands;
    private final Set<String> registeredNames = new LinkedHashSet<>();
    private final Set<String> collisions = new LinkedHashSet<>();

    public SkillManager(ToolRegistry registry, Agent agent, LlmClient client,
                        ProviderConfig config, Path projectRoot,
                        Supplier<String> systemPromptSupplier,
                        Supplier<List<ChatMessage>> historySupplier) {
        this.registry = registry;
        this.agent = agent;
        this.client = client;
        this.config = config;
        this.projectRoot = projectRoot;
        this.systemPromptSupplier = systemPromptSupplier;
        this.historySupplier = historySupplier;
        this.userDir = Path.of(System.getProperty("user.home"), ".mewcode", "skills");
        this.projectDir = projectRoot.resolve(".mewcode").resolve("skills");
        this.available = scanAndValidate();
        // use_skill 是系统级加载工具，恒注册（spec F6），不受任何白名单约束。
        registry.register(useSkillTool);
    }

    private Map<String, Skill> scanAndValidate() {
        SkillLoader loader = new SkillLoader();
        Map<String, Skill> skills = loader.load(BuiltinSkills.samples(), userDir, projectDir);
        for (Skill s : skills.values()) {
            for (String tool : s.tools()) {
                if (registry.get(tool) == null) {
                    // N3：白名单引用不存在的工具 → 启动期致命退出
                    throw new IllegalStateException(
                            "Skill '" + s.name() + "' 白名单引用不存在的工具: '" + tool + "'");
                }
            }
        }
        return skills;
    }

    // ── 查询 / 激活 ─────────────────────────────────────────────────────

    public Skill lookup(String name) {
        return name == null ? null : available.get(name.toLowerCase());
    }

    /** 激活（shared）：注册专属工具、更新白名单，返回替换 {args} 后的 SOP。未命中返回 null。 */
    public String activate(String name, String args) {
        Skill s = lookup(name);
        if (s == null) return null;
        for (Tool t : s.dedicatedTools()) registry.register(t);
        String sop = substitute(s.body(), args);
        active.put(s.name(), sop);
        agent.policy().setToolWhitelist(activeWhitelist());
        return sop;
    }

    /** 清空全部激活：卸载专属工具、复位白名单（spec F7 清空对话一并清空）。 */
    public void deactivateAll() {
        for (String name : active.keySet()) {
            Skill s = available.get(name);
            if (s != null) for (Tool t : s.dedicatedTools()) registry.remove(t.name());
        }
        active.clear();
        agent.policy().setToolWhitelist(Set.of());
    }

    /** 收窄后的白名单：非空 whitelist 并集 ∪ 专属工具 ∪ use_skill；无收窄返回空集。 */
    private Set<String> activeWhitelist() {
        Set<String> union = new LinkedHashSet<>();
        boolean anyWhitelist = false;
        for (String name : active.keySet()) {
            Skill s = available.get(name);
            if (s == null) continue;
            if (!s.tools().isEmpty()) { anyWhitelist = true; union.addAll(s.tools()); }
            for (Tool t : s.dedicatedTools()) union.add(t.name());
        }
        if (!anyWhitelist) return Set.of();
        union.add(USE_SKILL);
        return union;
    }

    /** 系统提示词的 Skill 段：可用菜单 +（激活时）已激活正文（spec F3/F4，priority 90）。 */
    public String buildSkillSection() {
        var sb = new StringBuilder();
        sb.append("## 可用 Skills（需要时用 ").append(USE_SKILL).append(" 工具按需加载完整指令）\n");
        for (Skill s : available.values()) {
            sb.append("- /").append(s.name()).append(" — ").append(s.description()).append("\n");
        }
        if (!active.isEmpty()) {
            sb.append("\n## 已激活 Skills（最高优先级，严格按其 SOP 执行）\n");
            for (Map.Entry<String, String> e : active.entrySet()) {
                sb.append("### Skill: ").append(e.getKey()).append("\n")
                  .append(e.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    // ── 独立模式（spec F5）──────────────────────────────────────────────

    /**
     * 开独立对话跑完 SOP，返回末条 assistant 文本作摘要。带 {@code history} 条近期主历史；
     * 子对话用全新 ToolRegistry（按白名单裁切）+ BYPASS 权限 + 无确认通道（主循环被阻塞，
     * HITL 会死锁；黑名单/沙箱仍在前置层生效）。
     */
    public String runIndependent(Skill skill, String args) {
        String sop = substitute(skill.body(), args);
        List<ChatMessage> sub = new ArrayList<>();
        int n = Math.max(0, skill.history());
        List<ChatMessage> main = historySupplier == null ? List.of() : historySupplier.get();
        if (n > 0 && main != null && !main.isEmpty()) {
            int from = Math.max(0, main.size() - n);
            sub.addAll(main.subList(from, main.size()));
        }
        sub.add(new ChatMessage(ChatMessage.Role.USER, sop));

        // 独立工具集：whitelist 非空则只取白名单 + 专属工具；否则全量 + 专属工具。
        ToolRegistry subRegistry = new ToolRegistry();
        if (skill.tools().isEmpty()) {
            for (Tool t : registry.listTools()) subRegistry.register(t);
        } else {
            for (String nm : skill.tools()) {
                Tool t = registry.get(nm);
                if (t != null) subRegistry.register(t);
            }
        }
        for (Tool t : skill.dedicatedTools()) subRegistry.register(t);

        Agent subAgent = new Agent(client, subRegistry, config.getProtocol(),
                new PermissionEngine(new Blacklist(), new PathSandbox(projectRoot),
                        new RuleEngine(), () -> PermissionMode.BYPASS_PERMISSIONS, null, null),
                new ContextManager(config));

        String sys = systemPromptSupplier == null ? null : systemPromptSupplier.get();
        if (sys == null || sys.isBlank()) sys = PromptBuilder.buildStableModules();
        BlockingQueue<AgentEvent> q = subAgent.run(sub, sys, false);
        drain(q);

        for (int i = sub.size() - 1; i >= 0; i--) {
            ChatMessage m = sub.get(i);
            if (m.getRole() == ChatMessage.Role.ASSISTANT && !m.hasToolCalls()
                    && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return "（独立执行未产生文本结果）";
    }

    private static void drain(BlockingQueue<AgentEvent> q) {
        try {
            while (true) {
                AgentEvent evt = q.poll(5, TimeUnit.MINUTES);
                if (evt == null || evt instanceof AgentEvent.LoopComplete) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 斜杠命令（spec F7）──────────────────────────────────────────────

    /** 注册 /skills + 各技能斜杠命令。撞名（含内置命令）跳过，内置优先。 */
    public void registerCommands(CommandRegistry commands) {
        this.commands = commands;
        commands.register(CommandSpec.of("skills", "列出可用与已激活的 Skill", "/skills",
                CommandType.LOCAL, null, false, (c, a) -> renderSkills(c)));
        registerSkillCommands();
    }

    private void registerSkillCommands() {
        if (commands == null) return;
        for (Skill s : available.values()) {
            String name = s.name();
            if (registeredNames.contains(name)) continue;
            if (commands.lookup(name) != null) { collisions.add(name); continue; }
            commands.register(CommandSpec.of(name, "Skill: " + s.description(), "/" + name,
                    CommandType.PROMPT, "[参数]", false,
                    (c, a) -> c.invokeSkill(name, a)));
            registeredNames.add(name);
        }
    }

    /** /skills 展示：名字 + 说明 + 模式 + 斜杠命令状态（撞名标注）。 */
    private void renderSkills(com.novacode.command.CommandContext c) {
        c.display("── Skills（" + available.size() + "）──");
        for (Skill s : available.values()) {
            String mode = s.mode() == SkillMode.INDEPENDENT ? "独立" : "共享";
            String slash = collisions.contains(s.name())
                    ? "（与内置命令同名，斜杠命令未注册）"
                    : "/" + s.name();
            String mark = active.containsKey(s.name()) ? " [已激活]" : "";
            c.display("  " + s.name() + " — " + s.description() + " [" + mode + "] " + slash + mark);
        }
    }

    /** 热更新：重扫三级目录、重建可用集、补注册新命令。返回人类可读结果。 */
    public String reload() {
        Map<String, Skill> before = available;
        this.available = scanAndValidate();
        active.keySet().retainAll(available.keySet());
        registerSkillCommands();
        agent.policy().setToolWhitelist(activeWhitelist());
        int newCount = 0;
        for (String name : available.keySet()) if (!before.containsKey(name)) newCount++;
        return "已重扫 Skill：" + available.size() + " 个可用" + (newCount > 0 ? "（新增 " + newCount + "）" : "");
    }

    // ── use_skill 加载工具（spec F3/F6）─────────────────────────────────

    private final Tool useSkillTool = new Tool() {
        @Override public String name() { return USE_SKILL; }
        @Override public String description() {
            return "按需加载一个 Skill 的完整指令（两阶段加载第二阶段）。shared 模式激活并返回完整 SOP；"
                    + "independent 模式在独立对话中执行后返回摘要。参数 name 为 Skill 名，args 替换 SOP 中的 {args}。";
        }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public Map<String, Object> schema() {
            return Map.of(
                    "name", USE_SKILL,
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "name", Map.of("type", "string", "description", "要加载的 Skill 名字"),
                                    "args", Map.of("type", "string", "description", "替换 SOP 中 {args} 占位符的内容（可选）")),
                            "required", List.of("name")));
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String nm = str(args, "name");
            String a = str(args, "args");
            Skill s = lookup(nm);
            if (s == null) {
                return ToolResult.error("未知 Skill: " + nm + "（可用：" + available.keySet() + "）");
            }
            if (s.mode() == SkillMode.INDEPENDENT) {
                try {
                    return ToolResult.success(runIndependent(s, a));
                } catch (Exception e) {
                    return ToolResult.error("独立执行失败: " + e.getMessage());
                }
            }
            String sop = activate(nm, a);
            return ToolResult.success(sop == null ? "" : sop);
        }
    };

    /** use_skill 工具句柄（供外部注册 / 测试）。 */
    public Tool useSkillTool() { return useSkillTool; }

    // ── Helpers ─────────────────────────────────────────────────────────

    static String substitute(String body, String args) {
        if (body == null) return "";
        String replacement = args == null || args.isBlank() ? "" : args;
        return body.replace("{args}", replacement);
    }

    private static String str(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object v = args.get(key);
        return v instanceof String s ? s : (v == null ? null : String.valueOf(v));
    }
}
