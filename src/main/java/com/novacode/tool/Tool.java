package com.novacode.tool;

import java.util.Map;

/** Unified tool interface. Each tool exposes metadata and an execute method. */
public interface Tool {
    String name();
    String description();
    ToolCategory category();
    Map<String, Object> schema();
    ToolResult execute(Map<String, Object> args);
}
