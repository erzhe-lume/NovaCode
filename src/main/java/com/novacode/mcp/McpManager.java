package com.novacode.mcp;

import com.novacode.tool.ToolRegistry;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 连接缓存与生命周期管理（第 7 章）。
 *
 * <p>持有所有已连接的 {@link McpClient}；连接、发现工具、关闭三件事都逐个容错，
 * 单个 Server 失败不影响其他（F8 / N2）。</p>
 */
public class McpManager {

    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    /** 连接全部 Server；单个失败只记入返回的错误映射，继续连下一个。 */
    public Map<String, String> connectAll(List<McpServerConfig> servers) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (McpServerConfig cfg : servers) {
            try {
                McpTransport transport = createTransport(cfg);
                McpClient client = new McpClient(cfg.name, transport);
                client.initialize();
                clients.put(cfg.name, client);
            } catch (Exception e) {
                errors.put(cfg.name, e.getMessage());
            }
        }
        return errors;
    }

    /** 对每个已连接 client 列出工具并包装注册；单个失败只报错，继续下一个。 */
    public void registerTools(ToolRegistry registry) {
        for (var entry : clients.entrySet()) {
            try {
                for (McpClient.McpToolDef def : entry.getValue().listTools()) {
                    registry.register(new McpToolWrapper(entry.getKey(), entry.getValue(), def));
                }
            } catch (Exception e) {
                System.err.println("[MCP] failed to list tools from '"
                        + entry.getKey() + "': " + e.getMessage());
            }
        }
    }

    /** 关闭所有连接（生命周期收尾）。 */
    public void shutdown() {
        for (McpClient c : clients.values()) c.close();
        clients.clear();
    }

    public int connectedCount() { return clients.size(); }

    private McpTransport createTransport(McpServerConfig cfg) throws IOException {
        if (cfg.isHttp()) return new HttpTransport(cfg);
        if (cfg.isStdio()) return new StdioTransport(cfg);
        throw new IllegalArgumentException(
                "MCP server '" + cfg.name + "' must specify either 'command' (stdio) or 'url' (HTTP)");
    }
}
