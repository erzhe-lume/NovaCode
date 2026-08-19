package com.novacode.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolResult;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 适配层：把单个远端 MCP 工具包装成 NovaCode 的 {@link Tool}（第 7 章）。
 *
 * <p>工具名统一为 {@code mcp__<server>__<tool>}（非法字符替换为 {@code _}），
 * 分类为 {@link ToolCategory#COMMAND}，Agent 调用时与内置工具完全无感。</p>
 */
public class McpToolWrapper implements Tool {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final McpClient client;
    private final McpClient.McpToolDef def;
    private final String fullName;

    public McpToolWrapper(String serverName, McpClient client, McpClient.McpToolDef def) {
        this.serverName = serverName;
        this.client = client;
        this.def = def;
        this.fullName = "mcp__" + sanitize(serverName) + "__" + sanitize(def.name());
    }

    @Override public String name() { return fullName; }

    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override public String description() {
        String d = def.description();
        return (d == null || d.isBlank())
                ? "MCP tool " + def.name() + " from server " + serverName
                : d;
    }

    @Override public Map<String, Object> schema() {
        return Map.of(
                "name", fullName,
                "description", description(),
                "input_schema", inputSchemaMap());
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        try {
            McpClient.McpCallResult r = client.callTool(def.name(), args);
            if (r.isError()) return ToolResult.error(r.text());
            return ToolResult.success(r.text());
        } catch (Exception e) {
            return ToolResult.error("MCP tool '" + def.name() + "' failed: " + e.getMessage());
        }
    }

    /** 把远端 inputSchema（JsonNode）转成 Anthropic 工具格式所需的 Map；缺失则给空对象 schema。 */
    private Map<String, Object> inputSchemaMap() {
        JsonNode node = def.inputSchema();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private static String sanitize(String s) {
        return NON_ALNUM.matcher(s == null ? "" : s).replaceAll("_");
    }
}
