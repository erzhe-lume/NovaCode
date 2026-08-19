package com.novacode.teams;

import com.novacode.agent.Agent;
import com.novacode.agent.AgentEvent;
import com.novacode.model.ChatMessage;
import com.novacode.subagent.SubAgentSpec;
import com.novacode.subagent.ToolFilter;
import com.novacode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 进程内队员常驻循环（第 15 章 F3/F6/F7）。
 *
 * <p>队员（同进程虚拟线程）反复：注入未读邮箱 → 跑一轮 Agent（计划审批前只读）→
 * 空闲轮询邮箱 → 新消息续跑 / 关闭请求应答退出。一轮自然停下列空闲通知 Lead（F6）；
 * 对话历史留在 {@link TeamManager.Member#history}，Lead 后续发消息即从磁盘上下文续跑，
 * 无需重新 spawn。需要审批的队员先以只读身份产出计划发审批请求，批准后才以可写身份执行
 * （F3/AC6）。</p>
 */
public final class TeammateRunner {

    public static final long IDLE_POLL_MS = 500;
    public static final String LEAD_NAME = TeamManager.LEAD_NAME;

    private TeammateRunner() {}

    // ── 核心循环 ──────────────────────────────────────────────────────────

    /**
     * 运行进程内队员循环（同步，直到收到关闭请求或线程被打断）。
     *
     * @param leadAgentId Lead 的收件箱投递标识（{@code team.leadInbox()}）。
     */
    public static void runInProcessTeammate(TeamManager teamManager, TeamManager.Team team,
                                            TeamManager.Member member, String systemPrompt,
                                            boolean needsApproval, String leadAgentId) {
        member.active = true;
        member.progress = TeammateProgress.initial().withStatus("running");
        try {
            boolean approved = false;
            while (true) {
                if (handleShutdown(team, member, leadAgentId)) return;

                boolean hasNew = injectPendingMessages(team, member);
                if (!hasNew) {
                    member.progress = member.progress.withStatus("idle");
                    Thread.sleep(IDLE_POLL_MS);
                    continue;
                }

                member.progress = member.progress.withStatus("working");
                String planText = runTurn(member, systemPrompt, needsApproval && !approved);
                member.progress = member.progress.withStatus("idle");

                if (needsApproval && !approved) {
                    member.progress = member.progress.withStatus("awaiting plan approval");
                    String requestId = TeamProtocol.newRequestId();
                    team.mailBox().send(leadAgentId, TeamProtocol.planApprovalRequest(
                            member.name, requestId, planText == null ? "(no plan produced)" : planText));
                    MailMessage decision = awaitPlanDecision(team, member, requestId);
                    if (TeamProtocol.approved(decision)) {
                        approved = true;
                        member.history.add(new ChatMessage(ChatMessage.Role.USER,
                                "<system-reminder>Plan approved. Proceed with execution.\n</system-reminder>"));
                        member.progress = member.progress.withStatus("working");
                        runTurn(member, systemPrompt, false);
                        member.progress = member.progress.withStatus("idle");
                    } else {
                        String feedback = decision == null ? null : decision.text();
                        if (feedback == null || feedback.isBlank()) {
                            feedback = "Revise your plan and resubmit for approval.";
                        }
                        member.history.add(new ChatMessage(ChatMessage.Role.USER,
                                "<system-reminder>Plan rejected. Feedback from lead: " + feedback
                                        + "\n</system-reminder>"));
                        continue;
                    }
                }

                team.mailBox().send(leadAgentId, createIdleNotification(member.name, "available"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            member.progress = member.progress.withStatus("error: " + e.getMessage());
        } finally {
            member.active = false;
            member.progress = member.progress.withStatus("completed");
            Agent a = member.agent;
            if (a != null) a.interrupt();
        }
    }

    /** 检测关闭请求：命中 → 应答后返回 true。 */
    static boolean handleShutdown(TeamManager.Team team, TeamManager.Member member, String leadAgentId) {
        for (MailMessage m : team.mailBox().list(member.name)) {
            if (TeamProtocol.isShutdownRequest(m)) {
                team.mailBox().send(leadAgentId, TeamProtocol.shutdownResponse(member.name, m.requestId()));
                return true;
            }
        }
        return false;
    }

    /** 把未读邮箱折叠成一条 system-reminder user 消息注入历史并标记已读；无未读返回 false。 */
    public static boolean injectPendingMessages(TeamManager.Team team, TeamManager.Member member) {
        List<MailMessage> unread = team.mailBox().readUnread(member.name);
        if (unread.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\nIncoming messages for you in team \"").append(team.name()).append("\":\n");
        for (MailMessage m : unread) {
            if (TeamProtocol.isShutdownRequest(m)) continue;
            sb.append("- from ").append(m.from()).append(": ").append(m.text()).append("\n");
        }
        sb.append("</system-reminder>");
        member.history.add(new ChatMessage(ChatMessage.Role.USER, sb.toString()));
        team.mailBox().markAllRead(member.name);
        return true;
    }

    /** 轮询邮箱等待匹配 requestId 的审批应答；线程被打断时向上抛（队员可被中止）。 */
    static MailMessage awaitPlanDecision(TeamManager.Team team, TeamManager.Member member, String requestId)
            throws InterruptedException {
        while (true) {
            for (MailMessage m : team.mailBox().list(member.name)) {
                if (TeamProtocol.PLAN_APPROVAL_RESPONSE.equals(m.type())
                        && requestId.equals(m.requestId())) {
                    return m;
                }
            }
            Thread.sleep(IDLE_POLL_MS);
        }
    }

    /** 跑一轮 Agent 并排空事件；返回模型文本（计划文本或执行汇报）。 */
    static String runTurn(TeamManager.Member member, String systemPrompt, boolean planMode)
            throws InterruptedException {
        Agent agent = member.agent;
        if (agent == null) return "";
        BlockingQueue<AgentEvent> queue = agent.run(member.history, systemPrompt, planMode);
        return drain(queue, member);
    }

    private static String drain(BlockingQueue<AgentEvent> queue, TeamManager.Member member)
            throws InterruptedException {
        var sb = new StringBuilder();
        while (true) {
            AgentEvent evt = queue.poll(120, TimeUnit.SECONDS);
            if (evt == null) break;
            switch (evt) {
                case AgentEvent.StreamText st -> sb.append(st.text());
                case AgentEvent.ToolResultEvent tre -> member.progress = member.progress.touch();
                case AgentEvent.ErrorEvent err -> sb.append("\n[agent error] ").append(err.message());
                case AgentEvent.LoopComplete lc -> { return sb.toString(); }
                default -> {}
            }
        }
        return sb.toString();
    }

    // ── 静态助手 ──────────────────────────────────────────────────────────

    /** 队员系统提示 = 基础提示（可空）+ 团队成员附加段。 */
    public static String buildSystemPrompt(String baseSystem, String teamName, String memberName,
                                           String leadAgentId, String worktreePath) {
        String addendum = buildTeammateAddendum(memberName, leadAgentId, teamName, worktreePath);
        if (baseSystem == null || baseSystem.isBlank()) return addendum;
        return baseSystem + "\n\n" + addendum;
    }

    /** 团队成员附加段：协作规则（SendMessage / Task 板 / 空闲通知）。 */
    public static String buildTeammateAddendum(String name, String leadAgentId, String teamName,
                                               String worktreePath) {
        String wt = worktreePath == null || worktreePath.isBlank()
                ? "" : "\n- Working directory: " + worktreePath;
        return """
                <teammate-notice>
                You are a team member named "%s" in team "%s". Your team lead is "%s".
                Rules:
                1. Use SendMessage to report progress to the lead (to: "lead") or message other members by name.
                2. Use TaskCreate/TaskList/TaskUpdate to coordinate shared work on the team task board.
                3. Do NOT use the Agent tool to spawn more agents; do your assigned work yourself.
                4. When your assigned work is complete, send an idle notification to the lead via SendMessage and stop.
                5. You remain resident: the lead can message you again later to continue from where you left off.
                %s
                </teammate-notice>
                """.formatted(name, teamName, leadAgentId, wt);
    }

    /** 空闲通知（F6）：{@code [idle] <name> (reason: available)}。 */
    public static MailMessage createIdleNotification(String name, String reason) {
        return new MailMessage(name, "[idle] " + name + " (reason: " + reason + ")");
    }

    /** 排空 Lead 收件箱：各团队的未读消息折叠成 team-notification user 消息，并标记已读。 */
    public static List<ChatMessage> drainLeadMailbox(TeamManager teamManager, String leadAgentId) {
        List<ChatMessage> injected = new ArrayList<>();
        for (TeamManager.Team team : teamManager.teams()) {
            if (!leadAgentId.equals(team.leadInbox())) continue;
            List<MailMessage> unread = team.mailBox().readUnread(leadAgentId);
            if (unread.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("<team-notification>\nMessages for the lead from team \"")
                    .append(team.name()).append("\":\n");
            for (MailMessage m : unread) {
                sb.append("- from ").append(m.from());
                if (!TeamProtocol.TEXT.equals(m.type())) sb.append(" [").append(m.type()).append("]");
                sb.append(": ").append(m.summary()).append("\n");
            }
            sb.append("</team-notification>");
            injected.add(new ChatMessage(ChatMessage.Role.USER, sb.toString()));
            team.mailBox().markAllRead(leadAgentId);
        }
        return injected;
    }

    /** 队员工具集：基础六工具（协作工具已挡掉）+ SendMessage + Task 四件套。 */
    public static ToolRegistry buildTeammateRegistry(ToolRegistry source, TeamManager teamManager,
                                                     String teamName, String memberName) {
        ToolRegistry reg = ToolFilter.filterForTeammate(source, null);
        reg.register(new TeamTools.SendMessageTool(teamManager, memberName, teamName));
        reg.register(new TeamTaskTools.TaskCreateTool(teamManager, teamName));
        reg.register(new TeamTaskTools.TaskGetTool(teamManager, teamName));
        reg.register(new TeamTaskTools.TaskListTool(teamManager, teamName));
        reg.register(new TeamTaskTools.TaskUpdateTool(teamManager, teamName));
        return reg;
    }
}
