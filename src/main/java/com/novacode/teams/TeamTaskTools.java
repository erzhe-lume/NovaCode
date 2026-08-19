package com.novacode.teams;

import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队共享任务板工具（第 15 章 F3/F5）：TaskCreate / TaskGet / TaskList / TaskUpdate。
 *
 * <p>团队解析规则：显式 {@code team_name} 参数优先；其次构造注入的默认团队名；
 * 都没有时若当前只有一个团队则用之，否则报错要求指定。队员由
 * {@link TeammateRunner#buildTeammateRegistry} 构造注入团队名；Lead 用显式参数。</p>
 */
public final class TeamTaskTools {

    private TeamTaskTools() {}

    /** 解析目标团队名；解析不出抛异常（调用方转错误结果）。 */
    static String resolveTeamName(TeamManager teamManager, String defaultTeamName, Map<String, Object> args) {
        String fromArgs = str(args, "team_name");
        if (fromArgs != null && !fromArgs.isBlank()) return fromArgs;
        if (defaultTeamName != null && !defaultTeamName.isBlank()) return defaultTeamName;
        List<String> teams = teamManager.listTeams();
        if (teams.size() == 1) return teams.get(0);
        throw new IllegalStateException("no team specified; pass 'team_name'");
    }

    /** 任务工具基类：团队解析 + taskStore 获取。 */
    abstract static class BaseTaskTool implements Tool {
        final TeamManager teamManager;
        final String defaultTeamName;

        BaseTaskTool(TeamManager teamManager, String defaultTeamName) {
            this.teamManager = teamManager;
            this.defaultTeamName = defaultTeamName;
        }

        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        SharedTaskStore storeFor(Map<String, Object> args) {
            String teamName = resolveTeamName(teamManager, defaultTeamName, args);
            TeamManager.Team team = teamManager.getTeam(teamName);
            if (team == null) throw new IllegalStateException("team not found: " + teamName);
            return team.taskStore();
        }

        void addTeamNameProperty(Map<String, Object> props) {
            props.put("team_name", Map.of("type", "string",
                    "description", "Optional team name (defaults to your team)."));
        }
    }

    /** 新建任务。 */
    public static final class TaskCreateTool extends BaseTaskTool {
        public TaskCreateTool(TeamManager teamManager, String defaultTeamName) {
            super(teamManager, defaultTeamName);
        }
        @Override public String name() { return "TaskCreate"; }
        @Override public String description() {
            return "Create a shared task in the team task board. Supports dependency fields blocks/blocked_by.";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            addTeamNameProperty(props);
            props.put("title", Map.of("type", "string", "description", "Task title."));
            props.put("description", Map.of("type", "string", "description", "Optional task description."));
            props.put("assignee", Map.of("type", "string", "description", "Optional assignee member name."));
            props.put("blocks", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Task ids this task blocks."));
            props.put("blocked_by", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Task ids this task is blocked by."));
            props.put("created_by", Map.of("type", "string", "description", "Optional creator name."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("title"));
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String title = str(args, "title");
            if (title == null || title.isBlank()) return ToolResult.error("Error: 'title' is required");
            try {
                SharedTaskStore st = storeFor(args);
                SharedTaskStore.SharedTask t = st.create(title, str(args, "description"),
                        str(args, "assignee"), strList(args, "blocks"),
                        strList(args, "blocked_by"), str(args, "created_by"));
                return ToolResult.success("created " + t.id() + " [" + t.status() + "]");
            } catch (Exception e) {
                return ToolResult.error("Error: " + e.getMessage());
            }
        }
    }

    /** 按 id 查任务。 */
    public static final class TaskGetTool extends BaseTaskTool {
        public TaskGetTool(TeamManager teamManager, String defaultTeamName) {
            super(teamManager, defaultTeamName);
        }
        @Override public String name() { return "TaskGet"; }
        @Override public String description() { return "Get a task by id from the team task board."; }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            addTeamNameProperty(props);
            props.put("id", Map.of("type", "string", "description", "Task id (e.g. task-1)."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String id = str(args, "id");
            if (id == null || id.isBlank()) return ToolResult.error("Error: 'id' is required");
            try {
                SharedTaskStore.SharedTask t = storeFor(args).get(id);
                return t == null ? ToolResult.error("Error: task not found: " + id)
                                 : ToolResult.success(describe(t));
            } catch (Exception e) {
                return ToolResult.error("Error: " + e.getMessage());
            }
        }
    }

    /** 列任务（按状态/归属过滤）。 */
    public static final class TaskListTool extends BaseTaskTool {
        public TaskListTool(TeamManager teamManager, String defaultTeamName) {
            super(teamManager, defaultTeamName);
        }
        @Override public String name() { return "TaskList"; }
        @Override public String description() {
            return "List shared tasks, optionally filtered by status (pending/in_progress/completed/blocked) and assignee.";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            addTeamNameProperty(props);
            props.put("status", Map.of("type", "string",
                    "description", "Optional filter: pending/in_progress/completed/blocked."));
            props.put("assignee", Map.of("type", "string", "description", "Optional filter by assignee."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            try {
                List<SharedTaskStore.SharedTask> list =
                        storeFor(args).listTasks(str(args, "status"), str(args, "assignee"));
                if (list.isEmpty()) return ToolResult.success("(no tasks)");
                StringBuilder sb = new StringBuilder();
                for (SharedTaskStore.SharedTask t : list) {
                    sb.append("- ").append(t.id()).append(" [").append(t.status()).append("]");
                    if (t.assignee() != null) sb.append(" assignee=").append(t.assignee());
                    sb.append(" ").append(t.title());
                    if (t.blocks() != null && !t.blocks().isEmpty()) sb.append(" blocks=").append(t.blocks());
                    if (t.blockedBy() != null && !t.blockedBy().isEmpty()) sb.append(" blocked_by=").append(t.blockedBy());
                    sb.append("\n");
                }
                return ToolResult.success(sb.toString().stripTrailing());
            } catch (Exception e) {
                return ToolResult.error("Error: " + e.getMessage());
            }
        }
    }

    /** 更新任务（状态/归属/描述/依赖追加去重）。 */
    public static final class TaskUpdateTool extends BaseTaskTool {
        public TaskUpdateTool(TeamManager teamManager, String defaultTeamName) {
            super(teamManager, defaultTeamName);
        }
        @Override public String name() { return "TaskUpdate"; }
        @Override public String description() {
            return "Update a task: status, assignee, description, or append dependency ids (add_blocks/add_blocked_by, deduplicated).";
        }
        @Override public Map<String, Object> schema() {
            Map<String, Object> props = new LinkedHashMap<>();
            addTeamNameProperty(props);
            props.put("id", Map.of("type", "string", "description", "Task id (e.g. task-1)."));
            props.put("status", Map.of("type", "string",
                    "description", "Optional: pending/in_progress/completed/blocked."));
            props.put("assignee", Map.of("type", "string", "description", "Optional assignee."));
            props.put("description", Map.of("type", "string", "description", "Optional description."));
            props.put("add_blocks", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Task ids to add to this task's blocks (deduplicated)."));
            props.put("add_blocked_by", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Task ids to add to this task's blocked_by (deduplicated)."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return TeamSchemas.wrap(name(), description(), schema);
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            String id = str(args, "id");
            if (id == null || id.isBlank()) return ToolResult.error("Error: 'id' is required");
            try {
                SharedTaskStore.SharedTask t = storeFor(args).update(id, str(args, "status"),
                        str(args, "assignee"), str(args, "description"),
                        strList(args, "add_blocks"), strList(args, "add_blocked_by"));
                return t == null ? ToolResult.error("Error: task not found: " + id)
                                 : ToolResult.success(describe(t));
            } catch (Exception e) {
                return ToolResult.error("Error: " + e.getMessage());
            }
        }
    }

    private static String describe(SharedTaskStore.SharedTask t) {
        return t.id() + " [" + t.status() + "]"
                + (t.assignee() == null ? "" : " assignee=" + t.assignee())
                + " " + t.title()
                + (t.description() == null ? "" : "\n  " + t.description())
                + (t.blocks().isEmpty() ? "" : "\n  blocks: " + t.blocks())
                + (t.blockedBy().isEmpty() ? "" : "\n  blocked_by: " + t.blockedBy());
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static List<String> strList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o instanceof String s && !s.isBlank()) out.add(s);
        return out;
    }
}
