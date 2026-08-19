package com.novacode.teams;

import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lead 侧团队工具（第 15 章 F3/F4/F5）：点对点发消息、建团队、删团队、合并分支。
 *
 * <p>这些工具只注册给主 Agent（Lead）；普通子 Agent 被 {@code ToolFilter.ALWAYS_DISALLOWED}
 * 挡掉，fork 也被 {@code cloneForFork} 剔除。队员通过 {@link TeammateRunner#buildTeammateRegistry}
 * 拿到 SendMessage + Task*（TeamCreate/Delete/TaskStop 不给队员）。</p>
 */
public final class TeamTools {

    private TeamTools() {}

    /** 队友名 → slug（与 Lead 派员时的命名规则一致）。 */
    public static String uniqueTeamName(TeamManager teamManager, String base) {
        String candidate = base;
        int i = 2;
        while (teamManager.getTeam(candidate) != null) {
            candidate = base + "-" + i++;
        }
        return candidate;
    }

    /**
     * 点对点 / 广播发消息（F4）。{@code to} 取值：成员名（经名称注册表解析）、
     * {@code "lead"}（发团队 Lead 收件箱）、{@code "*"}（广播同队其余成员）。
     */
    public static final class SendMessageTool implements Tool {
        private final TeamManager teamManager;
        private final String senderName;
        private final String defaultTeamName;

        public SendMessageTool(TeamManager teamManager, String senderName, String defaultTeamName) {
            this.teamManager = teamManager;
            this.senderName = senderName == null ? TeamManager.LEAD_NAME : senderName;
            this.defaultTeamName = defaultTeamName;
        }

        /** 便捷：不指定默认团队（发送时按 sender 解析，lead 需单个团队或走团队名）。 */
        public SendMessageTool(TeamManager teamManager, String senderName) {
            this(teamManager, senderName, null);
        }

        @Override public String name() { return "SendMessage"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public String description() {
            return "Send a point-to-point or broadcast message to a team member or the team lead. "
                    + "Set 'to' to a member name, \"lead\" for the team lead, or \"*\" to broadcast "
                    + "to all other members of your team. Messages are delivered asynchronously via the team mailbox.";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("to", Map.of("type", "string",
                    "description", "Recipient member name, \"lead\", or \"*\" for broadcast."));
            props.put("content", Map.of("type", "string", "description", "Message body."));
            props.put("type", Map.of("type", "string",
                    "description", "Optional protocol type: text, shutdown_request, shutdown_response, plan_approval_request, plan_approval_response."));
            props.put("request_id", Map.of("type", "string",
                    "description", "Optional request id for correlating protocol responses."));
            props.put("approve", Map.of("type", "boolean",
                    "description", "Optional approval flag for plan_approval_response."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("to", "content"));
            return TeamSchemas.wrap(name(), description(), schema);
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            String to = str(args, "to");
            String content = str(args, "content");
            if (to == null || to.isEmpty() || content == null || content.isEmpty()) {
                return ToolResult.error("Error: 'to' and 'content' are required");
            }
            String type = str(args, "type");
            if (type == null || type.isBlank()) type = TeamProtocol.TEXT;
            String requestId = str(args, "request_id");
            Boolean approve = bool(args, "approve");
            MailMessage msg = new MailMessage(senderName, content, Instant.now().toString(), false,
                    MailMessage.summaryOf(content), type, requestId, approve);

            String teamName = teamManager.senderTeamName(senderName, defaultTeamName);
            TeamManager.Team team = teamManager.getTeam(teamName);
            if (team == null) {
                return ToolResult.error("Error: team '" + teamName + "' not found for sender '" + senderName + "'");
            }

            int sent = 0;
            if ("*".equals(to)) {
                for (String other : team.memberNames()) {
                    if (other.equals(senderName)) continue;
                    team.mailBox().send(other, msg);
                    sent++;
                }
            } else if (TeamManager.LEAD_NAME.equals(to)) {
                team.mailBox().send(team.leadInbox(), msg);
                sent = 1;
            } else {
                String address = AgentNameRegistry.getInstance().resolve(to);
                if (address == null) address = to; // 未登记的成员（外部进程）按名投递
                if (team.hasMember(to) || !address.isEmpty()) {
                    team.mailBox().send(address, msg);
                    sent = 1;
                } else {
                    return ToolResult.error("Error: unknown member '" + to + "'");
                }
            }
            return ToolResult.success("sent to " + sent + " recipient(s)");
        }
    }

    /** 建团队：同名自动去重（name-2），运行后端按 {@link TeamManager#detectBackend} 自动选择。 */
    public static final class TeamCreateTool implements Tool {
        private final TeamManager teamManager;
        private final String leadAgentId;

        public TeamCreateTool(TeamManager teamManager, String leadAgentId) {
            this.teamManager = teamManager;
            this.leadAgentId = leadAgentId == null ? TeamManager.LEAD_NAME : leadAgentId;
        }

        @Override public String name() { return "TeamCreate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public String description() {
            return "Create a new long-lived team. Returns the team name and its runtime backend.";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("name", Map.of("type", "string", "description", "Team name."));
            props.put("description", Map.of("type", "string", "description", "Optional team description."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("name"));
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String name = str(args, "name");
            if (name == null || name.isBlank()) return ToolResult.error("Error: 'name' is required");
            String description = str(args, "description");
            String unique = uniqueTeamName(teamManager, name);
            TeamManager.TeamMode mode = TeamManager.detectBackend();
            teamManager.createTeam(unique, mode, leadAgentId, description);
            return ToolResult.success("created team \"" + unique + "\" (backend: " + mode + ")");
        }
    }

    /** 删团队：停所有成员 + 注销 + 删目录。 */
    public static final class TeamDeleteTool implements Tool {
        private final TeamManager teamManager;

        public TeamDeleteTool(TeamManager teamManager) { this.teamManager = teamManager; }

        @Override public String name() { return "TeamDelete"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public String description() {
            return "Delete a team: stop all members and remove its persisted directory.";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("name", Map.of("type", "string", "description", "Team name to delete."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("name"));
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String name = str(args, "name");
            if (name == null || name.isBlank()) return ToolResult.error("Error: 'name' is required");
            boolean ok = teamManager.deleteTeam(name);
            return ok ? ToolResult.success("deleted team \"" + name + "\"")
                      : ToolResult.error("Error: team \"" + name + "\" not found");
        }
    }

    /** 合并队员分支（F5）：{@link TeamMerge#merge}，冲突自动回滚并上报。 */
    public static final class TeamMergeTool implements Tool {
        private final Path gitRoot;

        public TeamMergeTool(Path gitRoot) { this.gitRoot = gitRoot; }

        @Override public String name() { return "TeamMerge"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public String description() {
            return "Merge a team member's worktree branch into the current branch. "
                    + "On unresolvable conflict, rolls back automatically (git merge --abort) and reports the conflicting files.";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("branch", Map.of("type", "string", "description", "Branch name to merge."));
            props.put("repo_path", Map.of("type", "string",
                    "description", "Optional git repository path (defaults to current working directory)."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("branch"));
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String branch = str(args, "branch");
            if (branch == null || branch.isBlank()) return ToolResult.error("Error: 'branch' is required");
            Path root = gitRoot != null ? gitRoot : Path.of(System.getProperty("user.dir"));
            String repoPath = str(args, "repo_path");
            if (repoPath != null && !repoPath.isBlank()) root = Path.of(repoPath);
            TeamMerge.MergeResult r = TeamMerge.merge(root, branch);
            // 冲突回滚是「报告」而非错误：把冲突文件交给模型处理
            return ToolResult.success(r.message());
        }
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static Boolean bool(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof Boolean b ? b : null;
    }
}
