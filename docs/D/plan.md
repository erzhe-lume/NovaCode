# MCP 客户端 Plan

## 架构概览

新增 `com.novacode.mcp` 包，五个职责分层清晰：

1. **配置层**：`McpServerConfig`（单个 Server 描述）+ `McpConfigLoader`（两级文件合并 + `${VAR}` 展开）。
2. **传输层**：`McpTransport` 接口 + `StdioTransport`（子进程 stdio）+ `HttpTransport`（Streamable HTTP）。负责「发送一段 JSON-RPC 请求，按 id 拿回匹配的响应」，屏蔽两种传输的差异。
3. **会话层**：`McpClient`（单个 Server 的会话）。负责 JSON-RPC 构造、自增 id、三步流程（initialize → notifications/initialized → tools/list / tools/call）、错误抽取。
4. **管理/缓存层**：`McpManager`。持有 `Map<String, McpClient>` 连接缓存，`connectAll`（逐个容错）、`registerTools`（发现并注册）、`shutdown`（生命周期收尾）。
5. **适配层**：`McpToolWrapper implements Tool`。把单个远端工具包装成 NovaCode 的 `Tool`，注册进 `ToolRegistry`。

在 `ChatModel` 构造器中：`ToolRegistry.createDefault()` 之后、`new Agent(...)` 之前接入——加载配置、连接、注册，确保 Agent 拿到的是已含 MCP 工具的完整注册表。

## 核心数据结构

### McpServerConfig（POJO）

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | Server 名（来自配置 map 的 key） |
| `command` | String | stdio：启动命令（如 `npx`） |
| `args` | List\<String\> | stdio：命令参数 |
| `env` | Map\<String,String\> | stdio：附加环境变量（值支持 `${VAR}`） |
| `url` | String | HTTP：MCP 端点 |
| `headers` | Map\<String,String\> | HTTP：附加请求头（值支持 `${VAR}`） |

判断类型：`command` 非空 → stdio；`url` 非空 → HTTP。

### McpTransport（接口）

```java
interface McpTransport extends AutoCloseable {
    String request(long id, String requestJson) throws IOException; // 发送请求，按 id 拿回响应 JSON 串
    void notify(String notificationJson) throws IOException;        // 发送无 id 通知，不等响应
    void close();
}
```

### McpClient（会话）

- 状态：`McpTransport transport`、`AtomicLong nextId`（自增 id）、`String serverName`。
- `void initialize()`：发 `initialize`，再发 `notifications/initialized`。
- `List<McpToolDef> listTools()`：发 `tools/list`。
- `McpCallResult callTool(String name, Map<String,Object> args)`：发 `tools/call`，抽取 `content[]` 的 text 与 `isError`。
- 内部 `JsonNode call(String method, Map params)`：构造请求、发、检查 `error` 字段、返回 `result`。

嵌套记录：`record McpToolDef(String name, String description, JsonNode inputSchema)`、`record McpCallResult(String text, boolean isError)`。

### McpToolWrapper（适配器）

```java
class McpToolWrapper implements Tool {
    String name();       // "mcp__" + sanitize(server) + "__" + sanitize(tool)
    ToolCategory category(); // COMMAND
    Map<String,Object> schema(); // { name, description, input_schema }
    ToolResult execute(Map args); // client.callTool(...) → ToolResult
}
```

## 模块设计

### 配置层：McpConfigLoader

**职责：** 读取两级文件，合并 `mcp_servers` map，展开 `${VAR}`，产出 `List<McpServerConfig>`。

- 用户级：`<user.home>/.novacode/config.yaml`
- 项目级：`<user.dir>/config.yaml`（即现有 provider 配置文件，只读其中的 `mcp_servers` 段）

**合并规则：** 用户级先读、项目级后读，同名 key 由项目级覆盖；key 即 `name`。任一文件缺失则跳过，不报错。

**展开规则：** `env` 与 `headers` 的值中形如 `${VAR}` 的部分替换为 `System.getenv(VAR)`；变量不存在时**保留原样**（比清空更可调试）。

### 传输层

**StdioTransport：** `ProcessBuilder` 启动 `command`+`args`，注入 `env`；`stdout` 读 JSON 行，`stdin` 写 JSON 行（每条一行，无内嵌换行）；`stderr` 用 `Redirect.DISCARD` 丢弃（避免日志混入 JSON 流或撑爆缓冲）。`request` 里写完请求后循环读行，直到读到 `id` 匹配且带 `result`/`error` 的行，无 id 的通知行跳过。子进程 `destroyOnExit()` 防止孤儿进程。请求用锁串行化（管道不能并发共享）。Windows 下 `npx`/`npm`/`node` 等命令补 `.cmd` 后缀。

**HttpTransport（Streamable HTTP）：** 对 `url` 发 POST，头带 `Content-Type: application/json`、`Accept: application/json, text/event-stream` 及配置的 `headers`；若有会话则带 `Mcp-Session-Id`。从响应头捕获 `Mcp-Session-Id` 存下。响应体若为 `text/event-stream`，解析 `data:` 行按 id 取响应；否则直接当 JSON-RPC 响应。非 2xx 抛错。

### 会话层：McpClient

自增 id 从 1 开始；`initialize` 携带 `protocolVersion: "2025-06-18"`、`capabilities: {}`、`clientInfo: {name:"novacode", version:"1.0.0"}`。`call` 收到 `error` 字段时抛 `IOException`，否则返回 `result`。`callTool` 从 `result.content[]` 里按类型抽取：`text` 拼文本，`image`/`resource` 用占位符标注；`result.isError` 为 true 时标记错误。

### 管理层：McpManager

- `Map<String, McpClient> connectAll(List<McpServerConfig>)`：逐个 `createTransport` + `initialize`，单个异常捕获进返回的 `Map<String,String>`（serverName → 错误信息），继续连下一个。
- `void registerTools(ToolRegistry)`：对每个已连上的 client 调 `listTools`，逐个包装成 `McpToolWrapper` 注册；单个失败捕获报错继续。
- `void shutdown()`：遍历关闭所有 client，清空缓存。

## 模块交互

```
ChatModel 构造器
  └─ McpConfigLoader.load() → List<McpServerConfig>
  └─ McpManager.connectAll(servers) → 每个 server:
        createTransport(cfg) → StdioTransport | HttpTransport
        McpClient(serverName, transport).initialize()
  └─ McpManager.registerTools(registry) → 每个 client:
        client.listTools() → McpToolDef[]
        registry.register(new McpToolWrapper(server, client, def))
  └─ new Agent(client, registry, ...)   // Agent 拿到含 MCP 工具的注册表

Agent 执行工具时:
  registry.get("mcp__server__tool").execute(args)
    └─ McpToolWrapper.execute → client.callTool(原名, args) → ToolResult
```

## 文件组织

```
src/main/java/com/novacode/mcp/
├── McpServerConfig.java   — 单个 Server 描述 POJO
├── McpConfigLoader.java   — 两级配置合并 + ${VAR} 展开
├── McpTransport.java      — 传输接口
├── StdioTransport.java    — 子进程 stdio 传输
├── HttpTransport.java     — Streamable HTTP 传输
├── McpClient.java         — 会话 + JSON-RPC + 三步流程（含 McpToolDef / McpCallResult）
├── McpToolWrapper.java    — 远端工具 → Tool 适配器
└── McpManager.java        — 连接缓存 + 生命周期
修改 src/main/java/com/novacode/ui/ChatModel.java — 接入加载/连接/注册
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 是否用官方 MCP SDK | 手写 JSON-RPC + 两种传输，不引 SDK | 用户需求明确要求「按 JSON-RPC 2.0 收发、请求带 id 回包按 id 配对」；符合项目从零构建理念 |
| 配置文件位置 | 用户级 `~/.novacode/config.yaml`，项目级 `./config.yaml` | 「配置文件」指现有 config.yaml（项目级）；用户级沿用 `~/.novacode/` 惯例（与权限配置同目录），两层合并 |
| 工具命名 | `mcp__<server>__<tool>`，非法字符→`_` | 与参考实现一致，避免与内置工具名冲突 |
| 工具分类 | `COMMAND` | 与参考实现一致；走既有串行执行 + 权限门路径 |
| 协议版本 | `protocolVersion: "2025-06-18"` | 生态广泛部署的稳定版本；协商结果由服务端响应返回 |
| `${VAR}` 缺失 | 保留原样 | 比清空更可调试，便于发现配置错误 |
| stderr 处理（stdio） | `Redirect.DISCARD` | 避免日志混入 JSON 流、避免缓冲满导致死锁 |
| 会话管理（HTTP） | 捕获并回传 `Mcp-Session-Id` | 符合 Streamable HTTP 会话约定；新版本服务端忽略也无害 |
| 并发（stdio） | 单客户端请求串行锁 | stdio 管道不能并发写/读，串行保证配对正确 |
