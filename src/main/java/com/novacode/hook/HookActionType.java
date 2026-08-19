package com.novacode.hook;

/** 动作类型（第 12 章 spec F4）：命令 / 提示词 / HTTP / 子 Agent（占位）。 */
public enum HookActionType {
    COMMAND("command"),
    PROMPT("prompt"),
    HTTP("http"),
    AGENT("agent");

    private final String value;

    HookActionType(String value) { this.value = value; }

    public String value() { return value; }

    public static HookActionType fromString(String s) {
        if (s == null) return null;
        for (HookActionType t : values()) {
            if (t.value.equals(s)) return t;
        }
        return null;
    }
}
