package com.novacode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Streamable HTTP 传输：对单一 MCP 端点发 POST，维护 {@code Mcp-Session-Id} 会话。
 *
 * <p>头带 {@code Accept: application/json, text/event-stream}，服务端可返回单 JSON 响应，
 * 也可返回 SSE 流；两种形态都按 id 取回匹配的 JSON-RPC 响应。</p>
 */
public class HttpTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String url;
    private final Map<String, String> headers;
    private volatile String sessionId;

    public HttpTransport(McpServerConfig cfg) {
        this.url = cfg.url;
        this.headers = cfg.headers != null ? cfg.headers : Map.of();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String request(long id, String requestJson) throws IOException {
        HttpResponse<String> resp = send(requestJson);
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        String body = resp.body();
        if (contentType.contains("text/event-stream")) {
            return extractFromSse(id, body);
        }
        return body;
    }

    @Override
    public void notify(String notificationJson) throws IOException {
        send(notificationJson); // 通知：只关心状态码，丢弃响应体
    }

    private HttpResponse<String> send(String json) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP HTTP request interrupted", e);
        }

        captureSession(resp);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("MCP HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp;
    }

    private void captureSession(HttpResponse<?> resp) {
        String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sid != null && !sid.isBlank()) sessionId = sid;
    }

    /** 从 SSE 流里找 id 匹配的 JSON-RPC 消息（每条 {@code data:} 行是一段完整消息）。 */
    private String extractFromSse(long id, String body) throws IOException {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;
            String data = trimmed.substring(5).trim();
            if (data.isEmpty()) continue;
            JsonNode node = MAPPER.readTree(data);
            if (node.has("id") && node.path("id").asLong() == id) return data;
        }
        throw new IOException("MCP HTTP SSE stream ended without a response for id " + id);
    }

    @Override
    public void close() {
        // 无持久连接需要关闭：每次请求都是独立的 HttpClient 请求
    }
}
