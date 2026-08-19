package com.novacode.command;

import java.util.List;

/**
 * 命令元数据（第 10 章 spec F1）。
 *
 * @param name        主命令名（不含斜杠，小写）
 * @param aliases     别名（小写）
 * @param description 简短描述（/help 用）
 * @param usage       用法示例，如 "/plan"
 * @param type        执行模式
 * @param paramHint   可选参数提示；无参数为 null
 * @param hidden      是否隐藏（不出现在 /help 与补全）
 * @param handler     处理函数
 */
public record CommandSpec(
        String name,
        List<String> aliases,
        String description,
        String usage,
        CommandType type,
        String paramHint,
        boolean hidden,
        CommandHandler handler) {

    /** 便捷工厂：别名用 varargs 传入。 */
    public static CommandSpec of(String name, String description, String usage,
                                 CommandType type, String paramHint, boolean hidden,
                                 CommandHandler handler, String... aliases) {
        return new CommandSpec(name, List.of(aliases), description, usage,
                type, paramHint, hidden, handler);
    }
}
