package com.novacode.teams;

/**
 * 队员运行进度快照（第 15 章 F1/F6）。
 *
 * <p>记录状态（idle / running / working / awaiting plan approval / completed / error…）、
 * 已执行工具数与最近活动时间戳。由队员循环每步更新，供 Lead 的 TaskStop/进度展示读取。</p>
 */
public record TeammateProgress(String status, int toolCount, long lastActiveAt) {

    public static TeammateProgress initial() {
        return new TeammateProgress("idle", 0, 0);
    }

    /** 改状态（不动计数）。 */
    public TeammateProgress withStatus(String s) {
        return new TeammateProgress(s, toolCount, System.currentTimeMillis());
    }

    /** 记录一次工具执行。 */
    public TeammateProgress touch() {
        return new TeammateProgress(status, toolCount + 1, System.currentTimeMillis());
    }
}
