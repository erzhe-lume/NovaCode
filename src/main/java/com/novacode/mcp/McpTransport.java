package com.novacode.mcp;

import java.io.IOException;

/**
 * MCP 传输抽象（第 7 章）。屏蔽 stdio 与 Streamable HTTP 两种底层差异，
 * 向上只暴露「发一段 JSON-RPC 请求、按 id 拿回匹配的响应」。
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送一条带 id 的 JSON-RPC 请求，返回 id 匹配的响应 JSON 串（含 result 或 error）。
     * 途中遇到的无 id 通知（或其它 id 的消息）由实现层跳过。
     */
    String request(long id, String requestJson) throws IOException;

    /** 发送一条无 id 的通知，不等待响应。 */
    void notify(String notificationJson) throws IOException;

    @Override
    void close();
}
