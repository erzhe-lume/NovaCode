package com.novacode.worktree;

import java.nio.file.Path;

/**
 * Worktree 改动检测（第 14 章 F7）。全部 fail-closed：git 命令异常时一律视为「有改动」，
 * 宁可保留也不误删。
 *
 * <p>「未推送提交」用「自创建基线 {@code base} 以来的新提交数」衡量，而非「有无上游」——
 * 新 worktree 分支天然无上游，若用后者判断会导致任何 worktree 都无法删除。</p>
 */
public final class WorktreeChanges {

    private WorktreeChanges() {}

    /** 是否有未提交改动（`git status --porcelain` 非空）；异常 → true。 */
    public static boolean hasLocalChanges(Path dir) {
        WorktreeManager.GitResult r = WorktreeManager.runGit(dir, "status", "--porcelain");
        if (!r.ok()) return true; // fail-closed
        return !r.out().isBlank();
    }

    /** 自基线 {@code base} 以来是否有新提交；基线缺失或异常 → true（fail-closed）。 */
    public static boolean hasNewCommits(Path dir, String base) {
        if (base == null || base.isBlank()) return true;
        WorktreeManager.GitResult r = WorktreeManager.runGit(dir, "rev-list", "--count", base + "..HEAD");
        if (!r.ok()) return true;
        try {
            return Integer.parseInt(r.out().trim()) > 0;
        } catch (NumberFormatException e) {
            return true; // fail-closed
        }
    }

    /** 有未提交改动或有新提交即为「有改动」。 */
    public static boolean hasChanges(Path dir, String base) {
        return hasLocalChanges(dir) || hasNewCommits(dir, base);
    }
}
