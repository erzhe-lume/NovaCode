package com.novacode;

import com.novacode.config.ConfigLoader;
import com.novacode.config.ProviderConfig;
import com.novacode.permission.Blacklist;
import com.novacode.permission.PathSandbox;
import com.novacode.permission.PermissionEngine;
import com.novacode.permission.PermissionMode;
import com.novacode.permission.RuleEngine;
import com.novacode.protocol.LlmClient;
import com.novacode.subagent.SubAgentRunner;
import com.novacode.teams.TeamManager;
import com.novacode.teams.TeammateRunner;
import com.novacode.tool.ToolRegistry;
import com.novacode.tui.tea.Program;
import com.novacode.ui.ChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** NovaCode entry point. */
public class App {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            // Always print to stderr (captured by launch.bat)
            System.err.println("NovaCode crashed: " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            System.err.println(sw);
            // Also write to a file for diagnosis
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("nova_crash.log"),
                    "NovaCode crashed: " + e + "\n" + sw
                );
            } catch (java.io.IOException ignored) {}
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        Path configPath = Path.of("config.yaml");
        if (args.length > 0) configPath = Path.of(args[0]);

        // 第 15 章：--teammate 入口（独立窗格队员进程）。
        if (Arrays.asList(args).contains("--teammate")) {
            runTeammate(args, configPath);
            return;
        }

        List<ProviderConfig> providers;
        try {
            providers = ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Use the selected provider (default: first; --provider <name|index> to choose)
        ProviderConfig cfg = selectProvider(providers, args);

        // 第 15 章 F7：coordinator 能力开关（与环境变量双锁判定在 ChatModel 内完成）。
        boolean coordinatorEnabled = ConfigLoader.coordinatorEnabled(configPath);
        ChatModel model = new ChatModel(cfg, coordinatorEnabled, configPath);
        Program program = new Program(model);
        program.run();
    }

    /** {@code --provider <name|index>}：按名称（忽略大小写/前缀）或 1 基下标选择 provider；
     *  缺省用第一个。找不到时列出可用项并退出。包私有以便测试。 */
    static ProviderConfig selectProvider(List<ProviderConfig> providers, String[] args) {
        String wanted = argValue(args, "--provider");
        if (wanted == null || wanted.isBlank()) return providers.get(0);
        ProviderConfig resolved = resolveProvider(providers, wanted);
        if (resolved != null) return resolved;

        System.err.println("Provider not found: " + wanted + ". Available providers:");
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig p = providers.get(i);
            System.err.println("  " + (i + 1) + ". " + p.getName()
                    + " (" + p.getProtocol() + " · " + p.getModel() + ")");
        }
        System.exit(1);
        return providers.get(0); // unreachable
    }

    /** 按名称精确 / 唯一前缀 / 1 基下标解析 provider；找不到返回 null（公共：/model 复用）。 */
    public static ProviderConfig resolveProvider(List<ProviderConfig> providers, String wanted) {
        for (ProviderConfig p : providers) {
            if (p.getName().equalsIgnoreCase(wanted)) return p;
        }
        List<ProviderConfig> byPrefix = providers.stream()
                .filter(p -> p.getName().toLowerCase().startsWith(wanted.toLowerCase()))
                .toList();
        if (byPrefix.size() == 1) return byPrefix.get(0);
        try {
            int idx = Integer.parseInt(wanted);
            if (idx >= 1 && idx <= providers.size()) return providers.get(idx - 1);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /** 窗格队员入口：从磁盘重建团队，以指定成员身份跑邮箱驱动循环（第 15 章 F2/F6）。 */
    private static void runTeammate(String[] args, Path configPath) {
        String teamName = argValue(args, "--team-name");
        String agentName = argValue(args, "--agent-name");
        if (teamName == null || agentName == null) {
            System.err.println("--teammate requires --team-name <name> and --agent-name <name>");
            System.exit(1);
            return;
        }
        try {
            List<ProviderConfig> providers = ConfigLoader.load(configPath);
            ProviderConfig cfg = providers.get(0);

            TeamManager tm = new TeamManager();
            TeamManager.Team team = tm.getTeam(teamName);
            if (team == null) {
                System.err.println("team not found: " + teamName);
                System.exit(1);
                return;
            }
            TeamManager.Member member = team.getMember(agentName);
            if (member == null) {
                System.err.println("member not found: " + agentName);
                System.exit(1);
                return;
            }

            LlmClient client = LlmClient.create(cfg, null);
            PermissionEngine perm = new PermissionEngine(new Blacklist(), new PathSandbox(Path.of(System.getProperty("user.dir"))),
                    new RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
            SubAgentRunner runner = new SubAgentRunner(client, cfg.getProtocol(), cfg, perm);
            ToolRegistry reg = TeammateRunner.buildTeammateRegistry(ToolRegistry.createDefault(), tm, team.name(), member.name);
            String sys = TeammateRunner.buildSystemPrompt(null, team.name(), member.name,
                    team.leadInbox(), member.worktreePath);
            member.agent = runner.buildTeammateAgent(reg, client, PermissionMode.DEFAULT, 0,
                    member.worktreePath == null ? null : Path.of(member.worktreePath));
            member.active = true;
            member.thread = Thread.currentThread();
            TeammateRunner.runInProcessTeammate(tm, team, member, sys, member.needsApproval, team.leadInbox());
            tm.closeAll();
        } catch (Exception e) {
            System.err.println("teammate failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /** 取 {@code --flag value} 形态参数值；缺失返回 null。 */
    private static String argValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return null;
    }
}
