package com.novacode.hook;

import java.time.Duration;
import java.util.Map;

/**
 * 动作定义（第 12 章 spec F4）。按 type 分用字段：
 * command → command；prompt → message；http → url/method/headers/body；agent → message（command 兜底）。
 */
public record HookAction(HookActionType type, String command, String message,
                         String url, String method, Map<String, String> headers,
                         String body, Duration timeout) {

    /** 便捷构造：仅 command/prompt/agent 类型用。 */
    public HookAction(HookActionType type, String command, String message) {
        this(type, command, message, null, null, null, null, Duration.ZERO);
    }
}
