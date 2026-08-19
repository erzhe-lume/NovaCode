package com.novacode.teams;

import java.util.Set;

/**
 * coordinator 模式开关（第 15 章 F7/N5/AC10）。
 *
 * <p><b>双锁生效</b>：配置能力开关（{@code enable_coordinator_mode}）为开 <b>且</b> 用户环境变量
 * {@code NOVACODE_COORDINATOR} ∈ {1, true}，两者同时满足才进入 coordinator 模式。</p>
 *
 * <p>生效后主 Agent 工具收窄为 {@link #ALLOWED_TOOLS}：只读工具 + shell（git 合并靠它）
 * + 调度/协调工具（Agent / SendMessage / Task* / TaskStop / TeamDelete / TeamMerge）；
 * 写文件工具（WriteFile / EditFile）被剥夺。</p>
 */
public final class Coordinator {

    /** coordinator 模式下主 Agent 允许的全部工具。 */
    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent", "SendMessage",
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "TaskStop", "TeamDelete", "TeamMerge",
            "ReadFile", "Glob", "Grep", "Bash");

    private Coordinator() {}

    /** 是否为 coordinator 允许的工具。 */
    public static boolean isCoordinatorTool(String name) {
        return name != null && ALLOWED_TOOLS.contains(name);
    }

    /** 环境变量是否启用：{@code NOVACODE_COORDINATOR} ∈ {1, true}。 */
    public static boolean envEnabled() {
        return envEnabled(System.getenv("NOVACODE_COORDINATOR"));
    }

    /** 可测版本：直接判环境变量值。 */
    public static boolean envEnabled(String envValue) {
        if (envValue == null) return false;
        String t = envValue.trim().toLowerCase();
        return "1".equals(t) || "true".equals(t);
    }

    /** 双锁判定：配置开关 && 环境变量。 */
    public static boolean active(boolean configEnabled) {
        return active(configEnabled, System.getenv("NOVACODE_COORDINATOR"));
    }

    /** 可测版本：显式传入环境变量值。 */
    public static boolean active(boolean configEnabled, String envValue) {
        return configEnabled && envEnabled(envValue);
    }
}
