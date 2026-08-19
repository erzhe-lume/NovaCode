# MCP 客户端 Checklist

> 每一项通过运行或观察行为验证。测试 Server 可用 `npx -y @modelcontextprotocol/server-filesystem <目录>`（stdio）或任意公开 Streamable HTTP 端点。

## 实现完整性

- [ ] `McpServerConfig` 六个字段 + `isStdio()`/`isHttp()` 就绪（验证：编译通过）
- [ ] `McpConfigLoader` 能读两级文件、按名合并、展开 `${VAR}`（验证：编译通过）
- [ ] `StdioTransport` / `HttpTransport` 实现 `McpTransport` 全部方法（验证：编译通过）
- [ ] `McpClient` 完成 initialize / listTools / callTool 三步（验证：编译通过）
- [ ] `McpToolWrapper` 实现 `Tool` 五方法（验证：编译通过）
- [ ] `McpManager` 完成 connectAll / registerTools / shutdown（验证：编译通过）

## 集成

- [ ] `ChatModel` 在 `new Agent` 之前完成加载/连接/注册（验证：编译通过）
- [ ] 工具名不冲突：`mcp__<server>__<tool>` 命名对非法字符替换为 `_`（验证：注册后 `ToolRegistry.get(name)` 能取到）
- [ ] 每个公开接口至少被一个真实调用方使用（验证：全量编译 + 无未使用告警）

## 编译与测试

- [ ] `mvn compile` 无错误、无告警（`-Xlint:all` 全开）

## 端到端场景

- [ ] **场景 1（stdio 全链路）**：在项目级 `config.yaml` 加 `mcp_servers: { fs: { command: npx, args: ["-y","@modelcontextprotocol/server-filesystem","."] } }` → 启动后让 Agent「列出 fs 服务器提供的工具并调用其中一个读文件」→ 能看到远端工具被执行、输出正确。
- [ ] **场景 2（HTTP 全链路）**：加一个 `url` 类型的 Server → 启动后其工具可被发现并可调用。
- [ ] **场景 3（容错）**：配置两个 Server，其中一个 `command` 写错 → 启动报出该 Server 失败，另一个 Server 工具仍可用。
- [ ] **场景 4（${VAR} 展开）**：`env` 或 `headers` 里写 `${SOME_VAR}`，先 `export SOME_VAR=...` → 启动后工具调用正常（变量已生效）。
- [ ] **场景 5（两级合并）**：用户级与项目级都配同名 Server → 以项目级配置为准；不同名则两者都注册。
- [ ] **场景 6（无配置降级）**：删掉所有 `mcp_servers` → 启动正常、内置工具照常工作、无报错。
