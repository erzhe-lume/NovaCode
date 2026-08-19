package com.novacode.teams;

import com.novacode.agent.Agent;
import com.novacode.permission.PermissionMode;
import com.novacode.protocol.LlmClient;
import com.novacode.subagent.SubAgentRunner;
import com.novacode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 队员派发器（第 15 章 F2）：按团队运行后端把队员派发到进程内虚拟线程或独立终端窗格。
 *
 * <p><b>不静默降级（F2）</b>：TMUX / ITERM 窗格启动失败 → 显式抛 {@link IllegalStateException}，
 * 绝不悄悄换成进程内。Windows 恒 IN_PROCESS（由 {@link TeamManager#detectBackend} 保证）。
 * 窗格队员通过 {@code java -jar <jar> --teammate --team-name <t> --agent-name <n>} 启动完整实例，
 * 从磁盘重建团队并接管同名邮箱。</p>
 */
public final class SpawnDispatcher {

    private SpawnDispatcher() {}

    /** 一次派发的全部参数。 */
    public record SpawnConfig(String teamName, String memberName, String prompt, String systemPrompt,
                              String model, String worktreePath, PermissionMode mode, int maxTurns,
                              boolean needsApproval, String leadAgentId) {}

    /** 派发队员：按 team.mode 分流。返回状态消息。 */
    public static String spawnTeammate(TeamManager teamManager, SubAgentRunner runner,
                                       ToolRegistry parentRegistry, LlmClient client, SpawnConfig cfg) {
        TeamManager.Team team = teamManager.getOrCreateTeam(cfg.teamName(), TeamManager.detectBackend(),
                cfg.leadAgentId(), "auto team");
        String memberName = uniqueMemberName(team, cfg.memberName());
        TeamManager.Member m = new TeamManager.Member(memberName);
        m.agentType = "teammate";
        m.model = cfg.model();
        m.needsApproval = cfg.needsApproval();
        m.worktreePath = cfg.worktreePath();
        team.addMember(m);
        teamManager.setMemberMeta(team, memberName, "teammate", cfg.model(),
                cfg.worktreePath(), cfg.needsApproval());

        // 初始任务写入队员邮箱：队员线程/窗格进程启动后经 injectPendingMessages 触发首轮，
        // 三种后端同构（F5：派员即开工，Lead 无需再补一条 "go"）。
        String body = cfg.prompt() == null || cfg.prompt().isBlank() ? "start" : cfg.prompt();
        team.mailBox().send(memberName, new MailMessage(team.leadInbox(), body));

        switch (team.mode()) {
            case IN_PROCESS -> {
                ToolRegistry reg = TeammateRunner.buildTeammateRegistry(parentRegistry, teamManager, team.name(), memberName);
                PermissionMode mode = cfg.mode() == null ? PermissionMode.DEFAULT : cfg.mode();
                Path sandbox = cfg.worktreePath() == null ? null : Path.of(cfg.worktreePath());
                Agent agent = runner.buildTeammateAgent(reg, client, mode, cfg.maxTurns(), sandbox);
                m.agent = agent;
                String sys = TeammateRunner.buildSystemPrompt(cfg.systemPrompt(), team.name(),
                        memberName, team.leadInbox(), cfg.worktreePath());
                Thread t = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(teamManager, team, m, sys,
                                cfg.needsApproval(), team.leadInbox()));
                m.thread = t;
                return "teammate " + memberName + " started in team " + team.name() + " (in-process)";
            }
            case TMUX, ITERM -> {
                return spawnPane(team, cfg, memberName);
            }
            default -> throw new IllegalStateException("unknown team mode: " + team.mode());
        }
    }

    /** 在独立终端窗格启动完整实例（tmux new-window / iTerm osascript）。失败显式报错。 */
    private static String spawnPane(TeamManager.Team team, SpawnConfig cfg, String memberName) {
        String cli = buildTeammateCLI(team.name(), memberName, cfg.worktreePath());
        try {
            if (team.mode() == TeamManager.TeamMode.TMUX) {
                Process p = new ProcessBuilder("tmux", "new-window", "-d", "-n", memberName, cli).start();
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new IllegalStateException("tmux new-window did not launch cleanly (exit " + p.exitValue() + ")");
                }
            } else {
                String script = "tell application \"iTerm\"\n\tcreate window with default profile command \""
                        + cli.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\nend tell";
                Process p = new ProcessBuilder("osascript", "-e", script).start();
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new IllegalStateException("iTerm failed to launch teammate (exit " + p.exitValue() + ")");
                }
            }
            return "teammate " + memberName + " started in team " + team.name()
                    + " in a " + team.mode() + " pane";
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot launch " + team.mode() + " teammate "
                    + memberName + ": " + e.getMessage());
        }
    }

    /** 窗格队员的 CLI：从当前 classpath 定位主 jar，构造完整实例启动串。 */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String cp = System.getProperty("java.class.path", "");
        String mainJar = firstJar(cp);
        List<String> parts = new ArrayList<>();
        if (mainJar != null) {
            parts.add("java -jar " + shellQuote(mainJar));
        } else {
            parts.add("java -cp " + shellQuote(cp) + " com.novacode.App");
        }
        parts.add("--teammate");
        parts.add("--team-name " + shellQuote(teamName));
        parts.add("--agent-name " + shellQuote(memberName));
        if (workdir != null && !workdir.isBlank()) parts.add("--cwd " + shellQuote(workdir));
        return String.join(" ", parts);
    }

    /** 从 classpath 里挑出第一个非目录、.jar 结尾的项（主 jar）。 */
    private static String firstJar(String classpath) {
        for (String part : classpath.split(java.io.File.pathSeparator)) {
            if (part.endsWith(".jar") && !part.endsWith("jline-3.28.0.jar")) return part;
        }
        return null;
    }

    /** 保守 shell 引号（POSIX 单引号 + 转义）。 */
    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 成员名 → 文件系统安全 slug（Lead 派员时给队员起名）。 */
    public static String slugify(String s) {
        if (s == null || s.isBlank()) return "member";
        String slug = s.trim().toLowerCase().replaceAll("[^a-zA-Z0-9_]+", "-");
        return slug.isBlank() ? "member" : slug;
    }

    /** 同名去重：base、base-2、base-3 … */
    public static String uniqueMemberName(TeamManager.Team team, String base) {
        String candidate = base;
        int i = 2;
        while (team.hasMember(candidate)) {
            candidate = base + "-" + i++;
        }
        return candidate;
    }
}
