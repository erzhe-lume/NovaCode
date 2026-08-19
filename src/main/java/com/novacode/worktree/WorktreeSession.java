package com.novacode.worktree;

import java.nio.file.Path;

/**
 * 一次 worktree enter 的会话记录（第 14 章 F3）。记录进入前的原始工作目录与分支、worktree
 * 位置与分支、进入时的基线提交、会话 id 与创建耗时。用于诊断、进程重启恢复，以及清理层
 * 跳过活动会话。
 */
public record WorktreeSession(
        Path originalCwd,
        Path worktreePath,
        String worktreeName,
        String worktreeBranch,
        String originalBranch,
        String originalHeadCommit,
        String sessionId,
        long creationDurationMs
) {}
