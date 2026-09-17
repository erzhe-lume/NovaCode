package com.novacode.permission;

import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Five-layer permission pipeline (spec F6): ① blacklist (command tools) →
 * ② sandbox (file tools) → ③ rule engine (three tiers) → ④ mode fallback →
 * ⑤ human-in-the-loop when the fallback is Ask. Any layer yielding a final
 * Allow/Deny short-circuits; skipped layers count as "not blocked, continue".
 *
 * <p>The pipeline is invoked before a tool executes. A Deny is returned as a
 * {@link Decision} with a readable reason tagged by source; the orchestrator
 * feeds it back to the model and keeps the loop going (spec F9). A user cancel
 * in HITL throws {@link PermissionCancelledException}.
 */
public class PermissionEngine {

    private final Blacklist blacklist;
    private final PathSandbox sandbox;
    private final RuleEngine rules;
    private final Supplier<PermissionMode> modeSupplier;
    private final PermissionPrompter prompter; // null → Ask degrades to Deny (safe default, N7)
    private final Path localRulesPath;         // permanent-allow write target

    public PermissionEngine(Blacklist blacklist, PathSandbox sandbox, RuleEngine rules,
                            Supplier<PermissionMode> modeSupplier, PermissionPrompter prompter,
                            Path localRulesPath) {
        this.blacklist = blacklist;
        this.sandbox = sandbox;
        this.rules = rules;
        this.modeSupplier = modeSupplier;
        this.prompter = prompter;
        this.localRulesPath = localRulesPath;
    }

    /**
     * 子 Agent 权限引擎工厂（第 13 章）：共享只读基础设施（blacklist/sandbox/rules/
     * localRulesPath），仅替换 mode supplier 与 prompter。子 Agent 无人在回路，
     * prompter 传 null —— Ask 降级为 Deny（安全默认，N7），规则/黑名单/沙箱仍在
     * 前置层照常生效。
     */
    public PermissionEngine forSubAgent(Supplier<PermissionMode> modeSupplier) {
        return new PermissionEngine(blacklist, sandbox, rules, modeSupplier, null, localRulesPath);
    }

    /** 换沙箱根（第 14 章）：共享其余字段，仅替换 sandbox —— 子 Agent worktree 隔离用。 */
    public PermissionEngine withSandbox(PathSandbox sandbox) {
        return new PermissionEngine(blacklist, sandbox, rules, modeSupplier, prompter, localRulesPath);
    }

    // ── Pipeline ──────────────────────────────────────────────────────────

    public Decision decide(Tool tool, Map<String, Object> args) {
        if (tool == null) {
            return Decision.deny("未注册工具，拒绝执行"); // N7: never silently allow
        }
        // INTERNAL（如 TodoWrite）：纯 Agent 私有状态，无用户可见副作用，免确认直通
        if (tool.category() == ToolCategory.INTERNAL) return Decision.allow();
        CallInfo info = extract(tool, args);

        // ① blacklist — command tools only
        if (info.category == ToolCategory.COMMAND) {
            String hit = blacklist.matches(info.subject);
            if (hit != null) return Decision.deny("黑名单: " + hit);
        } else {
            // ② sandbox — file tools only
            if (info.target == null)
                return Decision.deny("无法解析路径参数，拒绝执行"); // N7
            String violation = sandbox.check(info.target);
            if (violation != null) return Decision.deny("沙箱: " + violation);
        }

        // ③ rule engine (three tiers, deny beats allow, nearest tier wins)
        Decision rule = rules.match(info.friendlyName, info.subject);
        if (rule != null) return rule;

        // ④ mode fallback (value set {Allow, Ask} only)
        PermissionMode mode = modeSupplier.get();
        if (mode.allows(info.category)) return Decision.allow();

        // ⑤ human-in-the-loop
        return hitl(info, mode);
    }

    private Decision hitl(CallInfo info, PermissionMode mode) {
        if (prompter == null) {
            return Decision.deny("当前模式 " + mode.label() + " 需要确认，但无确认通道，拒绝执行");
        }
        AskContext ctx = new AskContext(
                info.toolName, preview(info.args), reasonFor(info.category, mode),
                info.friendlyName, info.subject);
        HitlOutcome outcome = prompter.ask(ctx);
        return switch (outcome) {
            case ALLOW_ONCE -> Decision.allow();
            case ALLOW_FOREVER -> {
                writePermanentRule(info.friendlyName, info.subject);
                yield Decision.allow();
            }
            case DENY -> Decision.deny("用户拒绝：你在确认框中拒绝了本次调用");
            case CANCELLED -> throw new PermissionCancelledException();
        };
    }

    // ── Subject extraction ────────────────────────────────────────────────

    private record CallInfo(String toolName, String friendlyName, ToolCategory category,
                            String subject, Path target, Map<String, Object> args) {}

    private CallInfo extract(Tool tool, Map<String, Object> args) {
        return switch (tool.name()) {
            case "Bash" -> new CallInfo("Bash", "Bash", ToolCategory.COMMAND,
                    str(args, "command", ""), null, args);
            case "ReadFile" -> fileInfo("ReadFile", "Read", ToolCategory.READ, args);
            case "WriteFile" -> fileInfo("WriteFile", "Write", ToolCategory.WRITE, args);
            case "EditFile" -> fileInfo("EditFile", "Edit", ToolCategory.WRITE, args);
            case "Glob" -> searchInfo("Glob", "Glob", ToolCategory.READ, args);
            case "Grep" -> searchInfo("Grep", "Grep", ToolCategory.READ, args);
            case "TodoWrite" -> new CallInfo("TodoWrite", "TodoWrite", ToolCategory.INTERNAL,
                    "", null, args);
            default -> new CallInfo(tool.name(), tool.name(), ToolCategory.COMMAND,
                    str(args, "command", ""), null, args); // unknown class → assume side effects (N7)
        };
    }

    private CallInfo fileInfo(String toolName, String friendly, ToolCategory cat,
                              Map<String, Object> args) {
        String raw = str(args, "file_path", "");
        if (raw.isBlank()) return new CallInfo(toolName, friendly, cat, "", null, args);
        Path abs = sandbox.resolve(Path.of(raw));
        return new CallInfo(toolName, friendly, cat, relSubject(abs), abs, args);
    }

    private CallInfo searchInfo(String toolName, String friendly, ToolCategory cat,
                                Map<String, Object> args) {
        String raw = str(args, "path", ".");
        Path abs = sandbox.resolve(Path.of(raw.isBlank() ? "." : raw));
        return new CallInfo(toolName, friendly, cat, relSubject(abs), abs, args);
    }

    /** Rule subject for file tools: project-relative path with '/' separators. */
    private String relSubject(Path abs) {
        Path root = sandbox.root();
        if (abs.startsWith(root)) {
            return root.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString(); // outside the project — sandbox will deny anyway
    }

    private static String preview(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        String key = args.containsKey("command") ? "command"
                : args.containsKey("file_path") ? "file_path"
                : args.entrySet().iterator().next().getKey();
        String val = String.valueOf(args.get(key));
        if (val.length() > 80) val = val.substring(0, 80) + "…";
        return key + "=" + val;
    }

    private static String reasonFor(ToolCategory category, PermissionMode mode) {
        return switch (category) {
            case COMMAND -> "命令执行类工具，当前模式 " + mode.label() + " 需要确认";
            case WRITE -> "文件写工具，当前模式 " + mode.label() + " 需要确认";
            case READ -> "只读工具，当前模式 " + mode.label() + " 需要确认";
            case INTERNAL -> "内部状态工具"; // decide() 已短路直通，此分支仅为穷举
        };
    }

    private void writePermanentRule(String friendlyName, String subject) {
        if (localRulesPath == null) return;
        String spec = friendlyName + "(" + escapeGlob(subject) + ")";
        PermissionFile file = RuleLoader.read(localRulesPath);
        if (file == null) file = new PermissionFile();
        if (file.getAllow() == null) file.setAllow(new java.util.ArrayList<>());
        if (file.getAllow().contains(spec)) return;
        file.getAllow().add(spec);
        try {
            RuleLoader.write(localRulesPath, file);
        } catch (IOException ignored) {
            // 落盘失败：本次仍放行（内存规则即时生效），只是不持久化
        }
        rules.addRule(RuleTier.LOCAL, Rule.of(spec, true, "本地配置"));
    }

    /** Escape glob metacharacters so an ALLOW_FOREVER rule matches the exact subject only. */
    private static String escapeGlob(String s) {
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private static String str(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
