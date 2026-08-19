package com.novacode.command;

/** 命令处理函数（第 10 章）：只依赖 {@link CommandContext}，不绑定渲染框架。 */
@FunctionalInterface
public interface CommandHandler {
    void handle(CommandContext ctx, String args);
}
