package com.novacode.command;

import java.util.Optional;

/** 斜杠命令解析（第 10 章 spec F2）。 */
public final class CommandParser {

    private CommandParser() {}

    /** 解析结果：命令名（小写）+ 参数。 */
    public record Parsed(String name, String args) {}

    /** 把一行输入解析成命令；不以斜杠开头、或命令名为空时返回空。 */
    public static Optional<Parsed> parse(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) return Optional.empty();
        String body = trimmed.substring(1).trim();
        if (body.isEmpty()) return Optional.empty();
        int sp = body.indexOf(' ');
        String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
        if (name.isEmpty()) return Optional.empty();
        String args = sp < 0 ? "" : body.substring(sp + 1).trim();
        return Optional.of(new Parsed(name, args));
    }
}
