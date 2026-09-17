package com.novacode.tool;

import com.novacode.tool.impl.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Central registry for tool registration, lookup, and schema generation. */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) { tools.put(tool.name(), tool); }

    public Tool get(String name) { return tools.get(name); }

    /** 卸载一个工具（目录型 Skill 专属工具随激活/去激活进出）。 */
    public void remove(String name) { tools.remove(name); }

    public List<Tool> listTools() { return List.copyOf(tools.values()); }

    /** Build the tools array for LLM API requests. */
    public List<Map<String, Object>> getAllSchemas(String protocol) {
        return buildSchemas(protocol, false, null);
    }

    /** Build schemas for all tools, filtered to {@code allowed} names (null = no filter). */
    public List<Map<String, Object>> getAllSchemas(String protocol, Set<String> allowed) {
        return buildSchemas(protocol, false, allowed);
    }

    /** Build schemas for read-only tools only (Plan Mode). */
    public List<Map<String, Object>> getReadOnlySchemas(String protocol) {
        return buildSchemas(protocol, true, null);
    }

    /** Build schemas for read-only tools, filtered to {@code allowed} names (null = no filter). */
    public List<Map<String, Object>> getReadOnlySchemas(String protocol, Set<String> allowed) {
        return buildSchemas(protocol, true, allowed);
    }

    private List<Map<String, Object>> buildSchemas(String protocol, boolean readOnly, Set<String> allowed) {
        boolean openAI = "openai".equals(protocol) || "openai-compat".equals(protocol);
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            // Plan Mode：只读工具 + INTERNAL（任务清单是规划过程的一部分）
            if (readOnly && tool.category() != ToolCategory.READ
                    && tool.category() != ToolCategory.INTERNAL) continue;
            if (allowed != null && !allowed.contains(tool.name())) continue;
            var base = tool.schema();
            if (openAI) {
                schemas.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                        "name", base.get("name"),
                        "description", base.get("description"),
                        "parameters", base.get("input_schema")
                    )
                ));
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

    public static ToolRegistry createDefault() {
        var reg = new ToolRegistry();
        reg.register(new ReadFileTool());
        reg.register(new WriteFileTool());
        reg.register(new EditFileTool());
        reg.register(new BashTool());
        reg.register(new GlobTool());
        reg.register(new GrepTool());
        reg.register(new TodoTool());
        return reg;
    }
}
