# Agent Loop Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `agent/AgentEvent.java` | sealed interface + record 事件类型 |
| 新建 | `agent/Agent.java` | ReAct 循环、executeBatched、事件发射 |
| 修改 | `protocol/StreamEvent.java` | 加 `Usage` 记录 |
| 修改 | `protocol/LlmClient.java` | 加 `setSystemSuffix()` |
| 修改 | `protocol/OpenAiCompatClient.java` | 注入 suffix、提取 usage |
| 修改 | `tool/ToolRegistry.java` | 加 `getReadOnlySchemas()` |
| 重写 | `ui/ChatModel.java` | Agent 集成、Plan Mode、多工具、用量 |

## T1: StreamEvent 加 Usage + LlmClient 加 systemSuffix

**文件：** `protocol/StreamEvent.java`、`protocol/LlmClient.java`
**依赖：** 无
**步骤：**
1. `StreamEvent.java`：新增 `record Usage(int inputTokens, int outputTokens) implements StreamEvent {}`
2. `LlmClient.java`：新增 `default void setSystemSuffix(String suffix) {}`

**验证：** `mvn compile` 通过

## T2: OpenAiCompatClient 注入 suffix + 提取 usage

**文件：** `protocol/OpenAiCompatClient.java`
**依赖：** T1
**步骤：**
1. 新增字段 `private String systemSuffix = ""`
2. `setSystemSuffix(String s)` → `this.systemSuffix = s`
3. `buildRequestBody()`：system prompt 拼 `prompt + (systemSuffix.isEmpty() ? "" : "\n\n" + systemSuffix)`
4. `handleSseData()`：解析 `root.path("usage")` JSON → 在 `[DONE]` 之前 emit `new StreamEvent.Usage(inputTokens, outputTokens)`

**验证：** `mvn compile` 通过

## T3: ToolRegistry 加 getReadOnlySchemas

**文件：** `tool/ToolRegistry.java`
**依赖：** 无
**步骤：**
1. 新增 `getReadOnlySchemas(String protocol)`：仿 `getAllSchemas()`，仅收 `tool.category() == ToolCategory.READ` 的项

**验证：** `mvn compile` 通过

## T4: AgentEvent 类型定义

**文件：** `agent/AgentEvent.java`（新建）
**依赖：** 无
**步骤：**
1. 创建 `com.novacode.agent` 包
2. 定义 `public sealed interface AgentEvent`，含 7 个 record：
   - `StreamText(String text)`
   - `ToolUseEvent(String toolId, String toolName, Map<String, Object> args)`
   - `ToolResultEvent(String toolId, String toolName, String output, boolean isError, double elapsedSec)`
   - `TurnComplete(int turn)`
   - `LoopComplete(int totalTurns)`
   - `UsageEvent(int inputTokens, int outputTokens)`
   - `ErrorEvent(String message)`

**验证：** `mvn compile` 通过

## T5: Agent 核心循环

**文件：** `agent/Agent.java`（新建）
**依赖：** T1, T2, T3, T4
**步骤：**
1. 定义常量 `MAX_ITERATIONS = 50`, `MAX_UNKNOWN_RUN = 3`
2. 构造函数注入 `LlmClient`, `ToolRegistry`, `String protocol`
3. `run(List<ChatMessage> history, String systemPrompt, boolean planMode)`：
   - 创建 `LinkedBlockingQueue<AgentEvent>(64)`
   - `Thread.startVirtualThread(() -> agentLoop(...))`
   - 返回 queue
4. `agentLoop()`：
   - 按 planMode 取工具集 + systemSuffix
   - `for iter = 1..MAX_ITERATIONS`：
     - emit `TurnComplete(iter)`，检查中断
     - `streamOnce()` 消费 LLM 流：TextDelta→累积+emit StreamText，ToolCallComplete→收集，Usage→记录，Error→emit ErrorEvent+返回 false
     - 无工具调用 → 写入 assistant 最终文本 → emit LoopComplete → return
     - 有工具调用 → 写入 assistant(tool_use) 到历史
     - `allUnknown()` 统计
     - `executeBatched()` 执行工具
     - 写入 tool_results 到历史
     - `!completed` → ensureAssistantTail → break
     - `unknownRun >= 3` → emit ErrorEvent → ensureAssistantTail → break
   - 触达上限 → emit ErrorEvent → ensureAssistantTail → emit LoopComplete
5. `executeBatched()`：
   - 扫描 calls，`ToolCategory.READ` 的连续段用 `Executors.newVirtualThreadPerTaskExecutor()` 并发，其他串行
   - 先按序 emit 所有 ToolUseEvent → 执行 → 再按序 emit ToolResultEvent（含耗时）
6. 辅助方法：`allUnknown()`, `ensureAssistantTail()`

**验证：** `mvn compile` 通过

## T6: ChatModel 集成 Agent Loop

**文件：** `ui/ChatModel.java`（重写工具执行部分）
**依赖：** T5
**步骤：**
1. 新增字段：`Agent agent`, `BlockingQueue<AgentEvent> agentQueue`, `boolean planMode`, `int currentTurn`, `int totalInputTokens/totalOutputTokens`, `List<ToolRun> runningTools`
2. 构造函数：初始化 `agent = new Agent(client, toolRegistry, config.getProtocol())`
3. `submit()` 改造：
   - `/plan` → `planMode = true`，添加提示块，回 idle
   - `/do` → `planMode = false`，注入 "请按上面的计划开始执行。"，走启动流程
   - `/exit` → 退出
   - 普通文本 → 添加 user 消息，启动 Agent
4. 启动 Agent：
   - 构建 history 列表
   - `agentQueue = agent.run(history, SYSTEM_PROMPT, planMode)`
   - `streaming = true`，启动 tick poll
5. 重写 `pollStream()` 为消费 AgentEvent：
   - `StreamText` → 追加到 streamBuf
   - `ToolUseEvent` → 添加到 runningTools
   - `ToolResultEvent` → 从 runningTools 移除，提交 tool result 消息到 msgs
   - `TurnComplete` → 更新 currentTurn
   - `UsageEvent` → 累加 totalInputTokens/totalOutputTokens
   - `LoopComplete` → 提交最终 assistant 答复到 msgs，streaming=false，回 idle
   - `ErrorEvent` → 提交错误消息
6. `view()` 改造：
   - 状态栏右侧：`↑{fmt(totalInputTokens)} ↓{fmt(totalOutputTokens)} tok`
   - 状态栏左侧：planMode 时显示 `[PLAN]` 徽标
   - 动态区：runningTools 非空时逐行 `● name(args) Running…`
   - 动态区：否则 `Thinking… (Ns · 第 N 轮)`

**验证：** `mvn compile` 通过

## T7: 编译打包 + 启动验证

**文件：** 无
**依赖：** T1-T6
**步骤：**
1. `mvn clean package -DskipTests` — 确认编译 + 打包通过
2. 启动 `powershell -Command Start-Process` 验证端到端

**验证：** JAR 生成成功，应用启动正常

## 执行顺序

```
T1 ──→ T2
T3 (并行)
T4 (并行)
T1+T2+T3+T4 ──→ T5 ──→ T6 ──→ T7
```
