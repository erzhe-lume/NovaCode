# MCP 客户端 Spec

## 背景

NovaCode 目前的内置工具（ReadFile / WriteFile / EditFile / Bash / Glob / Grep）是硬编码的，无法使用外部生态的工具。Model Context Protocol（MCP）是一套开放标准，让 AI 应用通过标准化的 JSON-RPC 2.0 协议接入外部数据源和工具。本章实现一个 MCP 客户端，让 NovaCode 在启动时自动发现并注册外部 MCP Server 提供的工具，Agent 调用时完全无感。

对照参考实现 MewCode：它使用官方 `io.modelcontextprotocol` Java SDK。NovaCode 的项目理念是从零构建、理解协议细节，因此本章**手写 JSON-RPC 2.0 收发与两种传输**，不引入 SDK 依赖；但沿用 MewCode 的命名约定与适配层结构（`mcp__<server>__<tool>` 工具名、`ToolCategory.COMMAND`、McpManager 生命周期）。

## 目标

- 启动时读取配置里声明的 MCP Server 列表，自动连接、发现并注册它们提供的工具。
- 支持两种传输：本地子进程走 stdio 管道，远程走 Streamable HTTP。
- 按 JSON-RPC 2.0 收发消息，请求带 `id`，回包按 `id` 异步配对。
- 一次会话三步：初始化握手 → 列出工具 → 调用工具。
- 用适配层把远端工具包装成已有 `Tool` 接口注册进工具中心，Agent 调用无感。
- 多个 Server 连接做缓存与生命周期管理，单个 Server 挂了不影响其他。

## 功能需求

- F1: 从配置文件读取 Server 列表。配置里用一个 map 声明 Server，每个 key 是 Server 名字；支持**用户级、项目级两层合并**，项目级覆盖用户级同名 Server。
- F2: stdio 类型 Server 配置 `command`、`args`、`env`（env 的值支持 `${VAR}` 展开）；客户端用子进程启动，按「每行一个 JSON」的 newline-delimited 方式在 stdin/stdout 收发。
- F3: HTTP 类型 Server 配置 `url`、`headers`（header 值支持 `${VAR}` 展开）；客户端走 Streamable HTTP（单 POST 端点，`Accept: application/json, text/event-stream`），维护 `Mcp-Session-Id` 会话。
- F4: 初始化握手。连接后先发 `initialize`（带 protocolVersion / capabilities / clientInfo），收到响应后发 `notifications/initialized`。
- F5: 列出工具。握手完成后发 `tools/list`，解析返回的 `tools[]`（name / description / inputSchema）。
- F6: 调用工具。发 `tools/call`（params.name / params.arguments），解析返回的 `content[]`（抽取 text 文本）与 `isError`。
- F7: 适配层。每个远端工具包装成 `Tool` 接口实例，名字为 `mcp__<server>__<tool>`（非法字符替换为 `_`），分类为 `COMMAND`，注册进 `ToolRegistry`。
- F8: 多 Server 生命周期。连接缓存于一个管理器；每个 Server 独立连接，某个连接失败只跳过该 Server 并报错，不影响其他；管理器提供关闭所有连接的入口。
- F9: 请求/响应配对。所有请求带自增数字 `id`，收到回包后按 `id` 匹配到对应的待处理请求；无 `id` 的通知消息直接跳过。

## 非功能需求

- N1: 不新增任何第三方依赖——JSON 用已有 Jackson，HTTP 用 JDK `java.net.http.HttpClient`，子进程用 `ProcessBuilder`。
- N2: 单个 Server 卡死或崩溃不拖垮整个程序：连接失败被捕获，子进程退出时调用能正常报错返回。
- N3: MCP 完全可选——没有配置、没有 `mcp_servers` 段、配置缺失任一文件时，NovaCode 照常启动，只跳过 MCP 功能。
- N4: 工具调用对 Agent 透明：`ToolResult` 的 `output` / `isError` 语义与内置工具一致，`isError=true` 时模型能据此修正重试。

## 不做的事

- MCP 的 resources（资源）、prompts（提示词）、sampling（采样）等非工具能力。
- Server 健康检查与自动重连。
- 工具列表的热更新 / `tools/listChanged` 订阅（只在启动时列一次）。
- 多 provider 选择（沿用现状，取第一个 provider）。
- 为 MCP 工具做细粒度权限分类（统一按 `COMMAND` 处理，走既有串行执行与权限门）。

## 验收标准

- AC1: 配置一个 stdio 类型 MCP Server，启动后其工具出现在 Agent 可调用集合中，Agent 能实际调用并拿到正确输出。
- AC2: 配置一个 HTTP（Streamable HTTP）类型 MCP Server，启动后其工具同样可被发现和调用。
- AC3: 同一 `mcp_servers` map 下配置多个 Server，其中一个连接失败（命令不存在 / url 不可达）时，其余 Server 的工具仍正常注册，且失败原因有可见报错。
- AC4: `env` 值与 `headers` 值中的 `${VAR}` 能被正确展开为对应环境变量。
- AC5: 用户级配置与项目级配置同时存在时，同名 Server 以项目级为准（覆盖），不同名 Server 合并。
- AC6: 完全没有 MCP 配置时，NovaCode 照常启动、正常对话，不报错、不崩溃。
- AC7: 每个功能需求 F1–F9 至少对应一条可观测的验证（见 checklist）。
