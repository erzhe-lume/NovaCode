# 子 Agent 委派 Plan

## 架构概览

新增 `com.novacode.subagent` 包，六个类型协同：

- **SubAgentSpec**：角色定义（record，含权限模式字段）。
- **AgentLoader**：三级来源加载 + 同名覆盖 + frontmatter 解析。
- **ToolFilter**：多层防线过滤，产出一份受限的 `ToolRegistry`。
- **SubAgentProgress**：进度事件（每次工具调用 + 完成各发一次）。
- **SubAgentTaskManager**：后台任务状态/结果/用量追踪 + 异步通知 + 取消。
- **AgentTool**：统一 `Agent` 工具，`subagent_type` 分流定义式 / Fork 式，同步 / 后台 / 超时转后台。
- **SubAgentRunner**：共享的「构造子 Agent + 跑到底 + 排空事件」编排，供 AgentTool 与 TaskManager 复用，消除重复。

子 Agent 复用 NovaCode 现有 `Agent` 循环（第 6 章，已具备「模型不再调工具即 LoopComplete」的跑到底语义）与 `PermissionEngine`（第 6 章，新增 `forSubAgent` 工厂做状态隔离）。对 `Agent` 做一处小改（`setMaxIterations`）以支持角色 `maxTurns`。

## 核心数据结构

### SubAgentSpec（record）

```java
public record SubAgentSpec(
    String name,              // 角色名
    String description,       // 用途说明
    List<String> tools,       // 工具白名单（空 = 不限）
    List<String> disallowedTools, // 工具黑名单
    String systemPrompt,      // 正文 → 子 Agent system prompt（空 = 用兜底）
    int maxTurns,             // 0 = 用 Agent 默认（50）
    String model,             // null/""/"inherit" = 继承；否则为模型别名
    PermissionMode permissionMode // null = 继承父权限模式；否则固定该模式
) {
    boolean inheritsMode() { return permissionMode == null; }
    // 内置：GENERAL_PURPOSE(inherit), PLAN(PLAN 只读), EXPLORE(PLAN 只读, model=haiku)
}
```

### ToolResult / Tool 接口（复用，第 6 章）

子 Agent 使用同一个 `Tool` 接口与 `ToolResult`（`success`/`error`）。

### SubAgentTaskManager 的 Task / Notification

```java
public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
public record Task(String id, String name, TaskStatus status, String output,
                   String error, int inputTokens, int outputTokens) {}
public record TaskNotification(String taskId, String name, TaskStatus status, String output) {}
```

## 模块设计

### SubAgentSpec.java
**职责**：角色定义 + 三个内置 spec 常量 + `inheritsMode()`。
**依赖**：`PermissionMode`（第 6 章）。

### AgentLoader.java
**职责**：`loadAll(projectRoot)` 依次加载内置 → 用户级 → 项目级；`listNames(map)` 排序返回；`parseAgentFile(path)` 解析单个 `.md`（frontmatter 用 jackson-dataformat-yaml 的 `YAMLMapper`）。
**依赖**：jackson `YAMLMapper`、`SubAgentSpec`。

### ToolFilter.java
**职责**：`filterForAgent(source, spec[, isAsync])` 产出受限注册表；`cloneForFork(source)` 复制父工具集并标记 AgentTool 防嵌套 fork。
- 全局禁止集 `ALWAYS_DISALLOWED = {"Agent", "use_skill"}`。
- 后台白名单 `BACKGROUND_ALLOWED = {"ReadFile","WriteFile","EditFile","Bash","Glob","Grep"}`。
- 定义式过滤：内置工具重建**全新实例 + 全新 FileStateCache**（状态隔离）；其余工具（MCP、Agent、Skill）复用实例。Fork：`cloneForFork` 全部复用实例（保 schema 一致）。
**依赖**：`ToolRegistry`、`Tool`、`FileStateCache`、各 `impl.*` 工具。

### SubAgentProgress.java
**职责**：进度 record（agentType/description/toolName/toolOutput/toolError/done/toolCount/totalTime）。

### SubAgentRunner.java
**职责**：共享编排。
- `Agent buildAgent(ToolRegistry, LlmClient, PermissionMode)`：`new Agent(client, registry, protocol, parentPermissionEngine.forSubAgent(() -> mode), new ContextManager(providerConfig))` 并 `setMaxIterations`。
- `LlmClient selectClient(specModel, overrideModel)`：override 优先，其次 specModel，`null`/`inherit`/空 → 父 client；否则 `modelResolver.apply(...)` 兜底父 client。
- `Outcome run(agent, history, systemPrompt, planMode, description, specName, timeoutSeconds)`：`agent.run(...)` 取队列，循环排空；StreamText 累积输出、ToolResultEvent 计数 + 发进度、UsageEvent 累积 token、ErrorEvent → 失败、LoopComplete → 完成；超时看门狗到点返回超时（可续跑，见下）。
**依赖**：`Agent`、`AgentEvent`、`ToolRegistry`、`PermissionEngine`、`ContextManager`。

### SubAgentTaskManager.java
**职责**：内存任务表 + 通知队列 + 取消。
- `createTask` / `setRunning` / `setCompleted` / `setFailed` / `cancelTask` / `drainNotifications` / `getTask` / `listTasks`。
- `spawnBackground(taskName, work, timeoutSeconds)`：开虚拟线程跑 `work`（`Supplier<SubAgentRunner.Outcome>`），按结果 `setCompleted`/`setFailed`；`timeoutSeconds` 看门狗到点打断并标记 FAILED。
- `continueInBackground(taskName, continuation)`：超时续跑续用（前台转后台，续跑同一队列）。
**依赖**：`SubAgentRunner.Outcome`、`Thread.startVirtualThread`。

### AgentTool.java（implements Tool）
**职责**：`name()="Agent"`，`category()=COMMAND`，`schema()` 生成含 `subagent_type` 枚举的 schema；`execute(args)` 分流：
1. 校验 `description`/`prompt`。
2. 无 `subagent_type` → **Fork**：查 `parentHistory`/`parentSystemPrompt`，`cloneForFork` 复制工具，复制历史 + fork 引导词，`spawnBackground`（强制后台），返回 task id。
3. 有 `subagent_type` → 解析 spec → 有 `run_in_background` 或 fork 不可用时走 `spawnBackground`；否则同步 `runner.run(...)` 返回内联结果。
4. `timeout_ms` 前台超时 → `continueInBackground` 转后台，返回「已转后台」。
**依赖**：`Tool`、`SubAgentSpec`、`AgentLoader`、`ToolFilter`、`SubAgentRunner`、`SubAgentTaskManager`、父依赖的 setter 注入（`setAgentSpecs` / `setModelResolver` / `setTaskManager` / `setPermissionEngine` / `setParentModeSupplier` / `setParentHistory` / `setParentSystemPrompt` / `setProgressListener` / `setForkDisabled` / `cloneWithQuerySource`）。

## 模块交互

```
主 Agent 循环（Agent.java，已有）
  └─ 模型请求工具 "Agent" ─▶ AgentTool.execute(args)
        ├─ 有 subagent_type ─▶ resolveSpec ─▶ ToolFilter.filterForAgent ─▶
        │       SubAgentRunner.run(同步) 或 SubAgentTaskManager.spawnBackground(后台)
        │            └─ 新 Agent 循环（隔离权限/上下文/计数）跑到底 ─▶ Outcome
        └─ 无 subagent_type ─▶ Fork：cloneForFork + 复制历史 + 强制 spawnBackground
                                    └─ 嵌套 fork 拦截（querySource 标记）
主 UI（ChatModel.pollAgent）在每轮 LoopComplete 后 drainNotifications ─▶
        └─ 后台任务完成通知注入主历史（<system-reminder>），供下一轮感知
```

## 文件组织

```
src/main/java/com/novacode/subagent/
├── SubAgentSpec.java        — 角色 record + 内置 spec
├── AgentLoader.java         — frontmatter 解析 + 三级加载 + 覆盖
├── ToolFilter.java          — 多层防线过滤 + cloneForFork
├── SubAgentProgress.java    — 进度 record
├── SubAgentRunner.java      — 构造子 Agent + 跑到底排空
├── SubAgentTaskManager.java — 后台任务表 + 通知 + 取消 + 超时看门狗
└── AgentTool.java           — 统一 Agent 工具 + 分流
src/main/java/com/novacode/agent/Agent.java          — 加 setMaxIterations
src/main/java/com/novacode/permission/PermissionEngine.java — 加 forSubAgent
src/main/java/com/novacode/ui/ChatModel.java          — 接线（注册 Agent 工具 + 排空通知）
target/smoke/Smoke13.java    — 验收（无真实 LLM）
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 子 Agent 复用主 `Agent` 循环 | 复用，而非新写循环 | 「模型不再调工具即 LoopComplete」已是跑到底语义，零重复 |
| 权限隔离 | `PermissionEngine.forSubAgent(mode)` 共享 blacklist/sandbox/rules、换 mode + 无 prompter | 规则配置是只读基础设施可共享；mode 与 HITL 是需隔离的状态 |
| 文件读缓存隔离 | 定义式重建全新工具实例 + 全新 FileStateCache | FileStateCache 是「先读后写」安全门，隔离保证子 Agent 必须自行读文件 |
| Fork 保 cache 命中 | `cloneForFork` 复用工具实例 + 复用父 system prompt | schema/前缀字节一致才命中 prompt cache |
| 嵌套 fork 拦截 | 复用实例上标记 `querySource`，运行时拦截 + 历史扫 `fork_boilerplate` 标签双保险 | 对话被摘要后 querySource 标记仍在（压缩安全） |
| `maxTurns` 支持 | 给 `Agent` 加 `setMaxIterations`（默认仍 50） | 角色 maxTurns 需可调；默认行为不变 |
| 超时转后台 | 前台 drain 看门狗到点 → `continueInBackground` 续跑同一队列 | 不重启、不丢已产出，语义最忠实 |
| frontmatter 解析 | jackson `YAMLMapper`（项目已有依赖） | 与 NovaCode 其余 YAML 配置同一技术栈，不新增依赖 |
| `haiku/sonnet/opus` 模型 | `selectClient` 透传给 modelResolver，无映射表 | 单 provider 场景由用户配置；映射留作扩展点 |
