package com.novacode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * stdio 传输：启动 Server 子进程，stdin/stdout 按「每行一个 JSON」收发。
 *
 * <p>stderr 用 DISCARD 丢弃：既避免日志混入 JSON 流，也避免日志撑爆缓冲导致死锁。
 * 子进程 {@code destroyOnExit()}，JVM 退出时不会留下孤儿进程。</p>
 */
public class StdioTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object lock = new Object();

    public StdioTransport(McpServerConfig cfg) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(windowsSafe(cfg.command));
        command.addAll(cfg.args);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (cfg.env != null && !cfg.env.isEmpty()) {
            pb.environment().putAll(cfg.env);
        }
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        this.process = pb.start();
        // 子进程无 destroyOnExit；用关闭钩子保证 JVM 退出时不留孤儿进程。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { process.destroy(); } catch (Exception ignored) {}
        }));
        this.reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public String request(long id, String requestJson) throws IOException {
        synchronized (lock) {
            write(requestJson);
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("MCP stdio server '" + process.info().commandLine()
                            + "' closed stdout unexpectedly");
                }
                if (line.isBlank()) continue;
                JsonNode node = MAPPER.readTree(line);
                if (node.has("id") && node.path("id").asLong() == id) {
                    return line;
                }
                // 无 id 的通知，或其它请求/响应 → 跳过
            }
        }
    }

    @Override
    public void notify(String notificationJson) throws IOException {
        synchronized (lock) {
            write(notificationJson);
        }
    }

    private void write(String json) throws IOException {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() {
        try { process.destroy(); } catch (Exception ignored) {}
        try { reader.close(); } catch (IOException ignored) {}
        try { writer.close(); } catch (IOException ignored) {}
    }

    /** Windows 下 npm 系 shim（npx/npm/pnpm/yarn）实际是 .cmd 脚本，
     *  ProcessBuilder 直接跑不到，需补 .cmd；node/uv/uvx 等是 .exe，勿补。 */
    static String windowsSafe(String command) {
        if (command == null) return null;
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return command;
        for (String s : new String[]{"npx", "npm", "pnpm", "yarn"}) {
            if (command.equalsIgnoreCase(s)) return command + ".cmd";
        }
        return command;
    }
}
