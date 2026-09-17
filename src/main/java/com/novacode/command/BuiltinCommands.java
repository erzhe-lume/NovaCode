package com.novacode.command;

import com.novacode.config.ProviderConfig;
import com.novacode.context.ContextManager;
import com.novacode.memory.MemoryManager;
import com.novacode.permission.PermissionConfig;
import com.novacode.permission.PermissionMode;
import com.novacode.permission.Rule;
import com.novacode.session.SessionStore;

import java.util.List;

/**
 * 内置命令装配（第 10 章 spec F7）：十个高频命令 + 既有 /new、/exit + 隐藏 /version。
 * 每个处理函数只依赖 {@link CommandContext}（或 build 传入的只读依赖），不绑定渲染框架。
 */
public final class BuiltinCommands {

    private static final String VERSION = "1.0.0";

    private BuiltinCommands() {}

    /** 缓存命中率片段（协议感知，与 ChatModel.cacheRateSuffix 同一套口径）。 */
    private static String cacheRate(ProviderConfig cfg, CommandContext.TokenStats t) {
        if (t.cacheRead() <= 0) return "";
        long denom = "anthropic".equals(cfg.getProtocol())
                ? (long) t.inputTokens() + t.cacheRead() + t.cacheWrite()
                : (long) t.inputTokens();
        if (denom <= 0) return "";
        return "（命中率 " + Math.round(100.0 * t.cacheRead() / denom) + "%）";
    }

    public static CommandRegistry build(ContextManager cm, SessionStore ss,
                                        MemoryManager mm, PermissionConfig pc,
                                        ProviderConfig cfg) {
        CommandRegistry registry = new CommandRegistry();

        // ── 纯本地 ──────────────────────────────────────────────────────
        registry.register(CommandSpec.of("help", "列出全部命令", "/help",
                CommandType.LOCAL, null, false, (c, args) -> {
                    c.display("── 可用命令 ──");
                    for (CommandSpec s : registry.visible()) {
                        String hint = s.paramHint() == null ? "" : " " + s.paramHint();
                        c.display("  " + s.usage() + hint + "  —  " + s.description());
                    }
                }, "?"));

        registry.register(CommandSpec.of("status", "显示会话、模式与 Token 用量", "/status",
                CommandType.LOCAL, null, false, (c, args) -> {
                    CommandContext.TokenStats t = c.tokens();
                    c.display("── 状态 ──");
                    c.display("  模式: " + c.getMode().label());
                    c.display("  模型: " + cfg.getModel());
                    c.display("  会话: " + ss.currentId());
                    c.display("  Token: ↑" + t.inputTokens() + " ↓" + t.outputTokens()
                            + " · cache读 " + t.cacheRead() + " / 写 " + t.cacheWrite()
                            + cacheRate(cfg, t));
                    c.display("  上下文: ~" + t.contextTokens() + " / " + t.contextWindow() + " tok");
                }));

        registry.register(CommandSpec.of("version", "显示版本号", "/version",
                CommandType.LOCAL, null, true, (c, args) ->
                        c.display("── NovaCode v" + VERSION + " ──")));

        // ── 影响界面状态 ────────────────────────────────────────────────
        registry.register(CommandSpec.of("clear", "清空消息区（保留对话历史）", "/clear",
                CommandType.UI_STATE, null, false, (c, args) -> {
                    c.clearMessages();
                    c.display("── 已清空消息区 ──");
                }, "cls"));

        registry.register(CommandSpec.of("plan", "进入计划模式（只读工具）", "/plan",
                CommandType.UI_STATE, null, false, (c, args) -> {
                    c.setMode(PermissionMode.PLAN);
                    c.display("── 已进入计划模式（只读工具）──");
                }));

        registry.register(CommandSpec.of("session", "列出历史会话（/session rm <id前缀> 删除）", "/session",
                CommandType.UI_STATE, "[rm <id前缀>]", false, (c, args) -> {
                    String trimmed = args == null ? "" : args.trim();
                    if (trimmed.toLowerCase().startsWith("rm ")) {
                        SessionStore.DeleteResult r = ss.deleteById(trimmed.substring(3).trim());
                        c.display("── " + r.message() + (r.ok() ? "" : "（未删除）") + " ──");
                        return;
                    }
                    List<SessionStore.SessionInfo> sessions = ss.list();
                    if (sessions.isEmpty()) {
                        c.display("── 没有历史会话 ──");
                    } else {
                        c.display("── 历史会话（最近在前，/resume <id前缀> 切换，/session rm <id前缀> 删除）──");
                        for (SessionStore.SessionInfo s : sessions) {
                            String title = s.firstMessage();
                            if (title.length() > 40) title = title.substring(0, 40) + "…";
                            boolean current = s.id().equals(ss.currentId());
                            c.display("  " + s.id() + (current ? " ← 当前" : "")
                                    + " · " + s.messageCount() + " 条 · " + title);
                        }
                    }
                }, "sessions"));

        registry.register(CommandSpec.of("model", "列出或切换 LLM provider", "/model",
                CommandType.UI_STATE, "[名称|序号]", false, (c, args) -> {
                    String trimmed = args == null ? "" : args.trim();
                    String result = trimmed.isEmpty() ? c.modelList() : c.switchModel(trimmed);
                    c.display("── " + result + " ──");
                }));

        registry.register(CommandSpec.of("memory", "显示当前记忆索引", "/memory",
                CommandType.UI_STATE, null, false, (c, args) -> {
                    c.display("── 记忆索引 ──");
                    for (String line : mm.buildMemorySection().split("\n", -1)) {
                        c.display(line.isEmpty() ? " " : "  " + line);
                    }
                }));

        registry.register(CommandSpec.of("permission", "显示当前权限模式与规则", "/permission",
                CommandType.UI_STATE, null, false, (c, args) -> {
                    c.display("── 权限模式: " + c.getMode().label() + " ──");
                    List<Rule> rules = pc.engine().allRules();
                    if (rules.isEmpty()) {
                        c.display("  无自定义规则（使用模式默认值）");
                    } else {
                        c.display("  已加载规则:");
                        for (Rule r : rules) {
                            c.display("  " + (r.allow() ? "allow " : "deny  ") + r.spec()
                                    + "  (" + r.source() + ")");
                        }
                    }
                }, "perm"));

        registry.register(CommandSpec.of("resume", "切换到历史会话（无参数列出）", "/resume",
                CommandType.UI_STATE, "[id前缀]", false, (c, args) -> {
                    if (args == null || args.isBlank()) {
                        List<SessionStore.SessionInfo> sessions = ss.list();
                        if (sessions.isEmpty()) {
                            c.display("── 没有历史会话 ──");
                            return;
                        }
                        c.display("── 历史会话（用 /resume <id前缀> 切换）──");
                        int shown = Math.min(sessions.size(), 8);
                        for (int i = 0; i < shown; i++) {
                            SessionStore.SessionInfo s = sessions.get(i);
                            String title = s.firstMessage();
                            if (title.length() > 36) title = title.substring(0, 36) + "…";
                            boolean current = s.id().equals(ss.currentId());
                            c.display("  " + s.id() + (current ? " ← 当前" : "")
                                    + " · " + s.messageCount() + " 条 · " + title);
                        }
                        return;
                    }
                    c.display("── " + c.resumeSession(args.trim()) + " ──");
                }));

        registry.register(CommandSpec.of("new", "开新会话（清空对话历史）", "/new",
                CommandType.UI_STATE, null, false, (c, args) ->
                        c.display("── 已开始新会话 " + c.newSession() + " ──")));

        registry.register(CommandSpec.of("exit", "退出程序", "/exit",
                CommandType.UI_STATE, null, false, (c, args) -> c.quit(),
                "quit", "q"));

        registry.register(CommandSpec.of("compact", "压缩对话上下文", "/compact",
                CommandType.UI_STATE, null, false, (c, args) ->
                        c.display("── " + c.compactContext() + " ──")));

        // ── 预设提示词 ──────────────────────────────────────────────────
        registry.register(CommandSpec.of("do", "切回执行模式并开始执行计划", "/do",
                CommandType.PROMPT, null, false, (c, args) -> {
                    c.setMode(PermissionMode.DEFAULT);
                    c.display("── 切回全工具模式，开始执行 ──");
                    c.sendPrompt("请按上面的计划开始执行。");
                }));

        registry.register(CommandSpec.of("review", "审查代码（可选范围）", "/review",
                CommandType.PROMPT, "[范围]", false, (c, args) -> {
                    String scope = args == null || args.isBlank() ? "当前项目" : args;
                    c.sendPrompt("请对「" + scope + "」做一次代码审查，重点看潜在 bug、"
                            + "安全问题与可简化的地方，并给出具体修改建议。");
                }));

        return registry;
    }
}
