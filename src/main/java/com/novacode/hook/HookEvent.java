package com.novacode.hook;

/**
 * Hook 生命周期事件（第 12 章 spec F2）——四层生命周期 + 系统级，共 9 种。
 */
public enum HookEvent {
    SHUTDOWN("shutdown"),
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    TURN_START("turn_start"),
    TURN_END("turn_end"),
    PRE_SEND("pre_send"),
    POST_RECEIVE("post_receive"),
    PRE_TOOL_USE("pre_tool_use"),
    POST_TOOL_USE("post_tool_use");

    private final String value;

    HookEvent(String value) { this.value = value; }

    public String value() { return value; }

    /** 按字符串查找，未知返回 null。 */
    public static HookEvent fromString(String s) {
        if (s == null) return null;
        for (HookEvent e : values()) {
            if (e.value.equals(s)) return e;
        }
        return null;
    }
}
