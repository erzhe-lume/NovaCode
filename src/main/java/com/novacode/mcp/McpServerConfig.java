package com.novacode.mcp;

import java.util.List;
import java.util.Map;

/**
 * 单个 MCP Server 的描述（第 7 章）。
 *
 * <p>一个 Server 要么走 stdio（{@code command} + {@code args} + {@code env}），
 * 要么走 Streamable HTTP（{@code url} + {@code headers}），二者取其一。
 * {@code name} 不来自配置内字段，而是配置 map 的 key，由 {@link McpConfigLoader} 回填。</p>
 */
public class McpServerConfig {

    /** Server 名（配置 map 的 key）。 */
    public String name;

    /** stdio：启动命令，如 {@code npx}。 */
    public String command;

    /** stdio：命令参数。 */
    public List<String> args = List.of();

    /** stdio：附加环境变量，值支持 {@code ${VAR}} 展开。 */
    public Map<String, String> env = Map.of();

    /** HTTP：MCP 端点 URL。 */
    public String url;

    /** HTTP：附加请求头，值支持 {@code ${VAR}} 展开。 */
    public Map<String, String> headers = Map.of();

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }
}
