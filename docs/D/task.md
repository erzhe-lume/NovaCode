# MCP 客户端 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/mcp/McpServerConfig.java` | 单个 Server 描述 POJO |
| 新建 | `src/main/java/com/novacode/mcp/McpConfigLoader.java` | 两级配置合并 + `${VAR}` 展开 |
| 新建 | `src/main/java/com/novacode/mcp/McpTransport.java` | 传输接口 |
| 新建 | `src/main/java/com/novacode/mcp/StdioTransport.java` | 子进程 stdio 传输 |
| 新建 | `src/main/java/com/novacode/mcp/HttpTransport.java` | Streamable HTTP 传输 |
| 新建 | `src/main/java/com/novacode/mcp/McpClient.java` | 会话 + JSON-RPC + 三步流程 |
| 新建 | `src/main/java/com/novacode/mcp/McpToolWrapper.java` | 远端工具 → Tool 适配器 |
| 新建 | `src/main/java/com/novacode/mcp/McpManager.java` | 连接缓存 + 生命周期 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 接入加载/连接/注册 |

## T1: McpServerConfig 配置 POJO

**文件：** `src/main/java/com/novacode/mcp/McpServerConfig.java`
**依赖：** 无
**步骤：**
1. 定义公开字段：`name`（String）、`command`（String）、`args`（List\<String\> = `List.of()`）、`env`（Map\<String,String\> = `Map.of()`）、`url`（String）、`headers`（Map\<String,String\> = `Map.of()`）。
2. 定义 `boolean isStdio()`（command 非空）与 `boolean isHttp()`（url 非空）。

**验证：** `mvn -q compile` 通过。

## T2: McpConfigLoader 两级合并

**文件：** `src/main/java/com/novacode/mcp/McpConfigLoader.java`
**依赖：** T1
**步骤：**
1. 用 Jackson YAML（SNAKE_CASE）定义内部包装类 `McpConfigFile { Map<String,McpServerConfig> mcp_servers; }`。
2. `load()`：依次合并 `<user.home>/.novacode/config.yaml`、`<user.dir>/config.yaml`；文件缺失跳过，`mcp_servers` 为空跳过；后读覆盖同名 key。
3. 遍历合并结果，`cfg.name = key`，对 `env`、`headers` 各值做 `${VAR}` 展开（缺失保留原样），返回 `List<McpServerConfig>`。

**验证：** `mvn -q compile` 通过。

## T3: McpTransport 接口

**文件：** `src/main/java/com/novacode/mcp/McpTransport.java`
**依赖：** 无
**步骤：**
1. 定义接口，继承 `AutoCloseable`：`String request(long id, String requestJson) throws IOException`、`void notify(String notificationJson) throws IOException`、`void close()`。

**验证：** `mvn -q compile` 通过。

## T4: StdioTransport 子进程传输

**文件：** `src/main/java/com/novacode/mcp/StdioTransport.java`
**依赖：** T1、T3
**步骤：**
1. 构造器：`windowsSafe(command)` 后 + args 组成命令；`ProcessBuilder` 注入 env；`redirectError(DISCARD)`；启动并 `destroyOnExit()`；拿 `stdin` 写流、`stdout` 读流。
2. `request(id, json)`：加锁，写一行 `json`，循环读 `stdout` 行，跳过空行与无匹配 id 的行，读到 id 匹配的行返回；EOF 抛 `IOException`。
3. `notify(json)`：加锁，写一行即可。
4. `close()`：`process.destroy()` 并关闭流。
5. 静态 `windowsSafe`：Windows 下 `npx/npm/node/uvx/uv/pnpm/yarn/bunx` 补 `.cmd`。

**验证：** `mvn -q compile` 通过。

## T5: HttpTransport Streamable HTTP

**文件：** `src/main/java/com/novacode/mcp/HttpTransport.java`
**依赖：** T1、T3
**步骤：**
1. 构造器：存 `url`、`headers`，建 `HttpClient`。
2. `request(id, json)`：POST，头 `Content-Type`/`Accept`/自定义 headers/可选 `Mcp-Session-Id`；捕获响应头里的 `Mcp-Session-Id` 存字段；非 2xx 抛错；`text/event-stream` 则解析 `data:` 行按 id 取响应，否则返回响应体。
3. `notify(json)`：POST 后丢弃响应体，非 2xx 抛错。
4. `close()`：空实现。

**验证：** `mvn -q compile` 通过。

## T6: McpClient 会话层

**文件：** `src/main/java/com/novacode/mcp/McpClient.java`
**依赖：** T3
**步骤：**
1. 定义嵌套记录 `McpToolDef(String name, String description, JsonNode inputSchema)`、`McpCallResult(String text, boolean isError)`。
2. 字段：`serverName`、`transport`、`AtomicLong nextId`、常量 `PROTOCOL_VERSION="2025-06-18"`。
3. `initialize()`：`call("initialize", {protocolVersion, capabilities:{}, clientInfo:{name:"novacode",version:"1.0.0"}})` 后 `notify` 发 `notifications/initialized`。
4. `listTools()`：`call("tools/list", {})`，解析 `result.tools[]`。
5. `callTool(name, args)`：`call("tools/call", {name, arguments})`，抽取 `content[]`（text 拼接，image/resource 占位）与 `isError`。
6. 私有 `call(method, params)`：构造 `{jsonrpc,id,method,params}`，`transport.request(id, json)`，解析回包，有 `error` 抛 `IOException`，否则返回 `result`。

**验证：** `mvn -q compile` 通过。

## T7: McpToolWrapper 适配器

**文件：** `src/main/java/com/novacode/mcp/McpToolWrapper.java`
**依赖：** T6、`Tool` 接口
**步骤：**
1. 实现 `Tool`；字段 `serverName`、`client`、`def`、`fullName`。
2. `name()` 返回 `"mcp__"+sanitize(server)+"__"+sanitize(tool)`。
3. `category()` 返回 `COMMAND`；`description()` 用工具描述，空则兜底。
4. `schema()` 返回 `{name, description, input_schema}`，`input_schema` 由 `JsonNode` 转 `Map`（空则 `{type:"object",properties:{}}`）。
5. `execute(args)`：`client.callTool(def.name(), args)`，`isError` 则 `ToolResult.error`，否则 `success`；异常捕获返回 `ToolResult.error`。

**验证：** `mvn -q compile` 通过。

## T8: McpManager 生命周期

**文件：** `src/main/java/com/novacode/mcp/McpManager.java`
**依赖：** T4、T5、T6、T7
**步骤：**
1. 字段 `Map<String,McpClient> clients`。
2. `connectAll(List<McpServerConfig>)`：逐个 `createTransport` + `McpClient.initialize()`，异常捕获进 `Map<String,String>`；返回错误映射。
3. `registerTools(ToolRegistry)`：逐个 `listTools` + `register(new McpToolWrapper(...))`，异常捕获报错继续。
4. `shutdown()`：遍历 `close()`，清空缓存。
5. 私有 `createTransport(cfg)`：按 `isHttp()/isStdio()` 分发，否则抛 `IllegalArgumentException`。

**验证：** `mvn -q compile` 通过。

## T9: 接入 ChatModel

**文件：** `src/main/java/com/novacode/ui/ChatModel.java`
**依赖：** T2、T8
**步骤：**
1. 新增字段 `private final McpManager mcpManager;`。
2. 在 `this.toolRegistry = ToolRegistry.createDefault();` 之后、`new Agent(...)` 之前：`mcpManager = new McpManager();`，`connectAll(McpConfigLoader.load())`，`registerTools(toolRegistry)`，连接错误逐条 `System.err` 打印。

**验证：** `mvn -q compile` 通过。

## 执行顺序

```
T1 → T2 ──┐
T3 ──► T4 ──┐
T3 ──► T5 ──┤──► T6 → T7 → T8 → T9
             │
             └────────────► (T2 并入 T9)
```

依赖链：T1→T2；T1/T3→T4；T1/T3→T5；T3→T6；T6→T7；T4/T5/T6/T7→T8；T2/T8→T9。
