package com.novacode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 MCP Server 的会话（第 7 章）。
 *
 * <p>负责 JSON-RPC 2.0 消息构造、自增 id、以及「初始化握手 → 列出工具 → 调用工具」
 * 三步流程。请求带 {@code id}，回包由传输层按 id 配对后再交回这里解析。</p>
 */
public class McpClient {

    /** 生态广泛部署的稳定协议版本。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final McpTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    public String serverName() { return serverName; }

    /** 初始化握手：发 initialize，收到响应后发 notifications/initialized。 */
    public void initialize() throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "novacode", "version", "1.0.0"));
        call("initialize", params);
        notify("notifications/initialized");
    }

    /** 列出工具：发 tools/list，解析 tools[] 的 name / description / inputSchema。 */
    public List<McpToolDef> listTools() throws IOException {
        JsonNode result = call("tools/list", Map.of());
        List<McpToolDef> tools = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            tools.add(new McpToolDef(
                    t.path("name").asText(""),
                    t.path("description").asText(""),
                    t.path("inputSchema")));
        }
        return tools;
    }

    /** 调用工具：发 tools/call，抽取 content[] 的文本与 isError。 */
    public McpCallResult callTool(String name, Map<String, Object> args) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args != null ? args : Map.of());
        JsonNode result = call("tools/call", params);

        boolean isError = result.path("isError").asBoolean(false);
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            boolean first = true;
            for (JsonNode c : content) {
                if (!first) sb.append('\n');
                first = false;
                switch (c.path("type").asText("")) {
                    case "text" -> sb.append(c.path("text").asText(""));
                    case "image" -> sb.append("[image: ").append(c.path("mimeType").asText("image")).append(']');
                    case "resource" -> sb.append("[resource: ")
                            .append(c.path("resource").path("uri").asText("")).append(']');
                    default -> sb.append(c.toString());
                }
            }
        }
        return new McpCallResult(sb.toString(), isError);
    }

    public void close() { transport.close(); }

    /** 发一条带 id 的请求，检查 error 字段，返回 result 节点。 */
    private JsonNode call(String method, Map<String, Object> params) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.put("params", params);

        JsonNode resp = MAPPER.readTree(transport.request(id, MAPPER.writeValueAsString(req)));
        if (resp.has("error")) {
            throw new IOException("MCP error (" + method + "): "
                    + resp.path("error").path("message").asText("unknown error"));
        }
        return resp.path("result");
    }

    /** 发一条无 id 的通知。 */
    private void notify(String method) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        transport.notify(MAPPER.writeValueAsString(req));
    }

    /** 远端工具定义（tools/list 返回的单个条目）。 */
    public record McpToolDef(String name, String description, JsonNode inputSchema) {}

    /** 工具调用结果（抽取出的文本 + 是否出错）。 */
    public record McpCallResult(String text, boolean isError) {}
}
