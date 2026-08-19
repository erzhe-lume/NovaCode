package com.novacode.teams;

import com.novacode.worktree.WorktreeManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 队员 worktree 分支合并 + 冲突回滚（第 15 章 F5/AC9）。
 *
 * <p>{@code git merge <branch>} 成功 → 干净合并；失败 → 解析未合并文件、{@code git merge --abort}
 * 回滚并上报冲突文件。全部经 {@link WorktreeManager#runGit} 完成。</p>
 */
public final class TeamMerge {

    /** 一次合并的结果。 */
    public record MergeResult(boolean ok, List<String> conflicts, boolean rolledBack, String message) {}

    private TeamMerge() {}

    /** 合并分支到当前分支；冲突自动回滚。 */
    public static MergeResult merge(Path gitRoot, String branch) {
        WorktreeManager.GitResult r = WorktreeManager.runGit(gitRoot, "merge", branch);
        if (r.ok()) {
            return new MergeResult(true, List.of(), false,
                    "merged " + branch + " into current branch");
        }
        List<String> conflicts = conflictFiles(gitRoot);
        WorktreeManager.runGit(gitRoot, "merge", "--abort");
        String msg = conflicts.isEmpty()
                ? "merge of " + branch + " failed and was rolled back: " + r.out().trim()
                : "merge of " + branch + " had conflicts; rolled back. conflicting files: " + conflicts;
        return new MergeResult(false, conflicts, true, msg);
    }

    /** 解析 {@code git status --porcelain} 中的未合并文件（U/U、AA、DD 状态）。 */
    public static List<String> conflictFiles(Path gitRoot) {
        WorktreeManager.GitResult r = WorktreeManager.runGit(gitRoot, "status", "--porcelain");
        if (!r.ok()) return List.of();
        List<String> files = new ArrayList<>();
        for (String line : r.out().split("\\R")) {
            if (line.isEmpty()) continue;
            if (line.length() < 2) continue;
            char x = line.charAt(0);
            char y = line.charAt(1);
            boolean unmerged = x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D');
            if (unmerged) {
                String p = line.length() > 3 ? line.substring(3).trim() : "";
                if (!p.isEmpty()) files.add(p);
            }
        }
        return files;
    }
}
