package com.novacode.hook;

/** pre_tool_use 拦截结果：是否拒绝 + 拒绝原因。 */
public record PreToolResult(boolean rejected, String message) {}
