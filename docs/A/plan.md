# Agent Loop Plan

> 基于已批准的 spec.md。本文档与语言相关（Java 21）。

## 架构概览

ch04 不新增包，在 ch03「protocol / tool / model / ui / tui/tea」之上**扩展**：

- **`com.novacode.agent`（新包）**：Agent 核心循环。`Agent.java` 持有 LLM 客户端 + 工具注册中心，`run()` 启动虚拟线程跑 ReAct 循环，通过 `BlockingQueue<AgentEvent>` 向 UI 推送事件。`AgentEvent.java` 用 sealed interface + record 定义所有事件类型。
- **`com.novacode.protocol`（扩展）**：`StreamEvent` 增 `Usage` 记录；`LlmClient` 增 `setSystemSuffix()`；`OpenAiCompatClient` 注入 suffix + 提取 usage。
- **`com.novacode.tool`（扩展）**：`ToolRegistry` 增 `getReadOnlySchemas()`，利用已有的 `ToolCategory.READ`。
- **`com.novacode.ui`（重写工具执行部分）**：`ChatModel` 替换单轮闭环为 Agent Loop 集成——`/plan`/`/do` 命令、消费 AgentEvent 流、多工具并发显示、用量/轮次展示。

依赖方向：`agent → {protocol, tool, model}`；`ui → {agent, protocol, tool, model}`。无环。

## 核心数据结构

### AgentEvent（新文件 `agent/AgentEvent.java`）

```java
public sealed interface AgentEvent {
    record StreamText(String text) implements AgentEvent {}
    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}
    record ToolResultEvent(String toolId, String toolName, String output, boolean isError, double elapsedSec)
        implements AgentEvent {}
    record TurnComplete(int turn) implements AgentEvent {}
    record LoopComplete(int totalTurns) implements AgentEvent {}
    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}
    record ErrorEvent(String message) implements AgentEvent {}
}
```

### StreamEvent 扩展（`protocol/StreamEvent.java`）

```java
// 新增：
record Usage(int inputTokens, int outputTokens) implements StreamEvent {}
```

### LlmClient 扩展（`protocol/LlmClient.java`）

```java
// 新增方法：
default void setSystemSuffix(String suffix) {}
```

### ToolRegistry 扩展（`tool/ToolRegistry.java`）

```java
// 新增方法：
public List<Map<String, Object>> getReadOnlySchemas(String protocol) {
    // 遍历 tools，仅收 ToolCategory.READ 的项
}
```

## 模块设计

### Agent（新文件 `agent/Agent.java`）

**职责：** ReAct 循环编排（F1/F2）、保序分批并发执行（F5）、事件流（F3/F8/F9）、终止历史一致性（F6）、Plan/Normal 模式（F10）。

**对外接口：**
```java
public class Agent {
    public Agent(LlmClient client, ToolRegistry registry, String protocol);
    public void setMaxIterations(int max);
    public BlockingQueue<AgentEvent> run(List<ChatMessage> history, String systemPrompt, boolean planMode);
}
```

**run() 算法（虚拟线程内）：**

1. 按 `planMode` 取工具集：`true` → `registry.getReadOnlySchemas(protocol)`，`false` → `registry.getAllSchemas(protocol)`
2. `unknownRun = 0`
3. `for iter = 1; iter <= maxIterations; iter++`:
   1. emit `TurnComplete(iter)`
   2. `streamOnce(history, tools, queue)` → `(text, calls, usage, ok)`
   3. 如果 `!ok` 且线程被中断 → emit `ErrorEvent("已取消")` → `ensureAssistantTail` → break
   4. 如果 `!ok` 且流出错 → `ensureAssistantTail` → break
   5. emit `UsageEvent`（如有）
   6. **无工具调用**：最终文本写入历史 → emit `LoopComplete(iter)` → return（自然完成）
   7. **有工具调用**：assistant(tool_use) 写入历史
   8. 统计未知工具：`allUnknown(calls)` → `unknownRun++`，否则 `unknownRun = 0`
   9. `executeBatched(calls, queue)` → `(results, completed)`
   10. tool_result 写入历史（无条件）
   11. 如果 `!completed`（取消）→ `ensureAssistantTail` → break
   12. 如果 `unknownRun >= maxUnknownRun` → emit error + `ensureAssistantTail` → break
4. 循环触达上限 → emit `ErrorEvent("已达最大迭代轮数")` → `ensureAssistantTail` → `LoopComplete`

**streamOnce()：** 遍历 `client.stream(history, systemPrompt)`：
- `TextDelta` → 累积 text + emit `StreamText`
- `ToolCallComplete` → 收集 calls
- `Usage` → 记录 usage
- `StreamEnd` → 记录 stopReason
- `Error` → emit `ErrorEvent`，返回 `ok=false`

**executeBatched()：** 从 `i=0` 扫描 calls：
- `calls[i]` 对应工具的 `category() == READ` → 向前吃连续 READ 得 `[i,j)` → **并发**执行（每工具一个虚拟线程，`newVirtualThreadPerTaskExecutor`）
- `calls[i]` 非 READ → **串行**执行单个
- 每段执行前检查 `Thread.interrupted()` → 取消时给未执行 call 填 "已取消" 结果
- 事件顺序：先按序 emit 所有 ToolUseEvent → 并发/串行执行 → 再按序 emit 所有 ToolResultEvent

**辅助函数：**
- `emit(queue, event)` — `queue.put(event)`，被中断则设置 interrupt flag
- `allUnknown(calls)` — 全部 toolName 在 registry 中不存在才返回 true
- `ensureAssistantTail(history, fallback)` — 若最后一条消息不是 assistant 角色，补一条 fallback 文本

### AgentEvent（新文件 `agent/AgentEvent.java`）

用 sealed interface + record。`sealed` 确保编译器穷举检查 switch 分支。

### ChatModel 改造（`ui/ChatModel.java`）

**新增字段：**
- `Agent agent`
- `BlockingQueue<AgentEvent> agentQueue`
- `boolean planMode`
- `int currentTurn`
- `int totalInputTokens`, `totalOutputTokens`
- `List<ToolRun> runningTools`（替换单个 curTool）

**submit() 改造：**
- `/exit` → 退出
- `/plan` → `planMode = true`，添加提示块，回 idle
- `/do` → `planMode = false`，注入执行指令，启动 Agent
- 普通文本 → 添加 user 消息，启动 Agent

**pollStream() 重写为 pollAgent()：**
```java
AgentEvent evt = agentQueue.poll();
switch (evt) {
    case StreamText st -> streamBuf.append(st.text());
    case ToolUseEvent tu -> runningTools.add(new ToolRun(tu));
    case ToolResultEvent tr -> runningTools.removeIf(t -> t.toolId.equals(tr.toolId()));
    case TurnComplete tc -> currentTurn = tc.turn();
    case UsageEvent ue -> { totalInputTokens += ue.inputTokens(); totalOutputTokens += ue.outputTokens(); }
    case LoopComplete lc -> { /* 提交最终答复，回 idle */ }
    case ErrorEvent ee -> { /* 显示错误 */ }
}
```

**view() 改造：**
- 状态栏：左侧 provider 名 + `[PLAN]` 徽标（planMode 时）
- 状态栏右侧：累计 token `↑1.2k ↓0.8k`
- 动态区：`runningTools` 非空 → 逐行渲染 `● toolName(args) Running…`
- 动态区：否则显示 `Thinking… (Ns · 第 N 轮)`（currentTurn > 0 时附轮次）

## 文件组织

```
src/main/java/com/novacode/
├── agent/
│   └── Agent.java          — 新建：ReAct 循环、executeBatched、事件发射
├── protocol/
│   ├── StreamEvent.java     — 修改：加 Usage 记录
│   ├── LlmClient.java       — 修改：加 setSystemSuffix()
│   └── OpenAiCompatClient.java — 修改：注入 suffix、提取 usage
├── tool/
│   └── ToolRegistry.java    — 修改：加 getReadOnlySchemas()
├── ui/
│   └── ChatModel.java       — 重写：Agent 集成、Plan Mode、多工具显示、用量
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Agent 运行位置 | 虚拟线程 + BlockingQueue | 与现有 LlmClient 一致，队列容量 64，UI 侧 poll 消费 |
| 事件类型定义 | sealed interface + record | 编译期穷举检查，switch 不漏分支；与现有 StreamEvent 一致 |
| 工具分类 | 沿用 `ToolCategory.READ` | 已有枚举，ReadFile/Glob/Grep 为 READ，WriteFile/EditFile 为 WRITE，Bash 为 COMMAND |
| Bash 分类 | `COMMAND`（有副作用） | 用户确认保守策略，Bash 串行执行 |
| Plan Mode 实现 | 仅注入 READ 工具 + system_reminder | spec 明确不做权限系统，纯粹靠工具集裁剪 + prompt 约束 |
| Plan Mode 工具集 | `getReadOnlySchemas()` 过滤 `ToolCategory.READ` | 物理上不给写/执行工具，模型无法调用 |
| 迭代上限 | `maxIterations = 50` | 兜底安全网，内置常量不可配 |
| 连续未知工具阈值 | `maxUnknownRun = 3` | 连续 3 轮全未知即停，混入已知工具重置计数 |
| 取消机制 | `Thread.interrupt()` + 循环检查 | Java 标准协作式取消，虚拟线程中断语义一致 |
| 用量提取 | 在 `OpenAiCompatClient.doStream()` 流结束后从 `usage` JSON 字段提取 | DeepSeek 在流结束时返回 usage，与 OpenAI 一致 |
| 历史一致性 | `ensureAssistantTail()` 保证末尾是 assistant 文本 | 取消/出错后角色必须交替，否则下轮请求 400 |
| mode 状态存放 | ChatModel 字段 | mode 是会话级 UI 状态，跨轮保持，不放 Conversation |
| 并发执行 | `Executors.newVirtualThreadPerTaskExecutor()` | Java 21 原生 API，每个工具一个虚拟线程，IO 不阻塞 OS 线程 |
