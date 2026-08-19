package com.novacode.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 目录型 Skill 的专属工具（第 11 章 spec F8）：schema 来自 JSON，实现来自 bash 脚本。
 * 执行时把参数序列化为 JSON 写进脚本 stdin，收集 stdout 作为结果。
 * category = READ —— 不触发权限确认（脚本由 Skill 作者提供，视为可信）。
 */
public class ScriptTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final String scriptPath;

    public ScriptTool(String name, String description, Map<String, Object> inputSchema, String scriptPath) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema == null ? Map.of("type", "object", "properties", Map.of()) : inputSchema;
        this.scriptPath = scriptPath;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public ToolCategory category() { return ToolCategory.READ; }

    @Override public Map<String, Object> schema() {
        return Map.of("name", name, "description", description, "input_schema", inputSchema);
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        try {
            String json = MAPPER.writeValueAsString(args == null ? Map.of() : args);
            ProcessBuilder pb = new ProcessBuilder("bash", scriptPath);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var stdin = p.getOutputStream()) {
                stdin.write(json.getBytes(StandardCharsets.UTF_8));
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ToolResult.error("Error: script timed out after 60s");
            }
            return new ToolResult(output, p.exitValue() != 0);
        } catch (Exception e) {
            return ToolResult.error("Error running skill tool: " + e.getMessage());
        }
    }
}
