package com.novacode.teams;

/**
 * coordinator 模式每轮注入的调度指引（第 15 章 F7/N5）。
 *
 * <p>上下文有界策略（N5）：首轮与每 5 轮注入全文，其余轮注入精简复述，
 * 避免 8KB 全文逐条追加吃掉收窄省下的上下文。</p>
 */
public final class CoordinatorPrompt {

    private CoordinatorPrompt() {}

    public static String fullPrompt() {
        return """
                <coordinator-reminder>
                You are the Team Lead in COORDINATOR MODE. Your job is to dispatch and direct, not to edit files yourself.
                Available tools: Agent (spawn teammates), SendMessage (talk to teammates), TaskCreate/TaskList/TaskUpdate (coordinate the shared task board), TaskStop, TeamDelete, TeamMerge, plus read-only tools and Bash (for git merging).
                Your file-write tools (WriteFile/EditFile) are DISABLED in this mode. Do NOT create, edit, or delete files directly.
                Workflow:
                1. Break the user's goal into dependent tasks and create them on the shared task board (TaskCreate with blocks/blocked_by).
                2. Spawn one teammate per task with Agent (team_name = the team, subagent_type = a worker type, plan_mode_required = true for tasks that need plan approval).
                3. Monitor progress via SendMessage and task updates; reassign tasks to resolve blocks.
                4. When all teammates report idle, merge their worktree branches with TeamMerge; resolve simple conflicts via Bash (git). If a conflict cannot be resolved, roll back with git merge --abort and report the conflicting files.
                5. Report the final result to the user.
                </coordinator-reminder>""";
    }

    public static String briefPrompt(int iter) {
        if (iter == 1 || iter % 5 == 0) return fullPrompt();
        return "<coordinator-reminder>\nCoordinator mode active: dispatch teammates, coordinate via SendMessage and the shared task board, merge work when done. Do not edit files directly (write tools disabled).\n</coordinator-reminder>";
    }

    public static String buildReminder(int iter) {
        return briefPrompt(iter);
    }
}
