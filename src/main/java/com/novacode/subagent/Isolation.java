package com.novacode.subagent;

/**
 * 子 Agent 隔离模式（第 14 章 F6）。角色 frontmatter 的 {@code isolation} 字段：
 * {@code none}（默认，与主 Agent 共享工作目录）或 {@code worktree}（独立 Git worktree）。
 * 未知值一律降级为 {@link #NONE}（安全默认）。
 */
public enum Isolation {
    NONE,
    WORKTREE;

    public static Isolation parse(String s) {
        if (s == null || s.isBlank()) return NONE;
        return switch (s.strip().toLowerCase()) {
            case "worktree" -> WORKTREE;
            case "none", "inherit", "" -> NONE;
            default -> NONE;
        };
    }
}
