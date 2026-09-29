# 子 Agent 委派 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/subagent/SubAgentSpec.java` | 角色 record + 内置 spec |
| 新建 | `src/main/java/com/novacode/subagent/AgentLoader.java` | frontmatter 解析 + 三级加载 |
| 新建 | `src/main/java/com/novacode/subagent/ToolFilter.java` | 多层防线过滤 + cloneForFork |
| 新建 | `src/main/java/com/novacode/subagent/SubAgentProgress.java` | 进度 record |
| 新建 | `src/main/java/com/novacode/subagent/SubAgentRunner.java` | 构造子 Agent + 跑到底排空 |
| 新建 | `src/main/java/com/novacode/subagent/SubAgentTaskManager.java` | 后台任务表 + 通知 + 取消 |
| 新建 | `src/main/java/com/novacode/subagent/AgentTool.java` | 统一 Agent 工具 + 分流 |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | 加 setMaxIterations |
| 修改 | `src/main/java/com/novacode/permission/PermissionEngine.java` | 加 forSubAgent |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 接线 + 排空通知 |
| 新建 | `target/smoke/Smoke13.java` | 验收（无真实 LLM） |

## T1: SubAgentSpec + 内置角色

**文件：** `subagent/SubAgentSpec.java`
**依赖：** 无
**步骤：**
1. 定义 record `SubAgentSpec(name, description, tools, disallowedTools, systemPrompt, maxTurns, model, permissionMode)`。
2. 加 `inheritsMode()`（permissionMode == null）。
3. 定义内置：`GENERAL_PURPOSE`（permissionMode=null 继承）、`PLAN`（permissionMode=PLAN，disallowedTools=["WriteFile","EditFile"]，maxTurns=15，含只读 system prompt）、`EXPLORE`（permissionMode=PLAN，disallowedTools=["WriteFile","EditFile"]，model="haiku"）。

**验证：** `mvn -q compile` 通过；`Smoke13` 中可读三个内置常量字段。

## T2: AgentLoader（frontmatter + 三级加载 + 覆盖）

**文件：** `subagent/AgentLoader.java`
**依赖：** T1
**步骤：**
1. `loadAll(Path projectRoot)`：内置 → `~/.novacode/agents` → `<projectRoot>/.novacode/agents`，`LinkedHashMap` put 覆盖。
2. `parseAgentFile(Path)`：读文本，按 `---` 切 frontmatter 与正文；`YAMLMapper` 解析 frontmatter 到 `Map`，取 name/description/tools/disallowedTools/model/maxTurns/permissionMode。
3. name/description 缺失抛异常；`model` 标准化 `inherit`（忽略大小写）；`permissionMode` 经 `PermissionMode.parse`（"inherit"/空 → null）。
4. 单个文件异常在 `loadDir` 内吞掉跳过；`listNames` 排序。

**验证：** `mvn -q compile` 通过；Smoke13 用临时目录写 `.md` 验证解析 + 覆盖 + 坏文件跳过。

## T3: ToolFilter（多层防线 + cloneForFork）

**文件：** `subagent/ToolFilter.java`
**依赖：** T1
**步骤：**
1. 常量 `ALWAYS_DISALLOWED = {"Agent","use_skill"}`、`BACKGROUND_ALLOWED = {"ReadFile","WriteFile","EditFile","Bash","Glob","Grep"}`。
2. `filterForAgent(source, spec, isAsync)`：遍历 source，跳过全局禁止 → 跳过 spec.disallowedTools → 应用 spec.tools 白名单 → isAsync 时应用后台白名单；内置六工具重建全新实例 + 全新 FileStateCache，其余复用。
3. `cloneForFork(source)`：复制全部工具，`AgentTool` 用 `cloneWithQuerySource("agent:builtin:fork")` 替换。

**验证：** `mvn -q compile` 通过；Smoke13 验证全局禁止/黑名单/白名单/后台白名单/全新实例。

## T4: SubAgentProgress

**文件：** `subagent/SubAgentProgress.java`
**依赖：** 无
**步骤：** 定义 record `(agentType, description, toolName, toolOutput, toolError, done, toolCount, totalTime)`。

**验证：** `mvn -q compile` 通过。

## T5: Agent.setMaxIterations

**文件：** `agent/Agent.java`
**依赖：** 无
**步骤：**
1. 加 `private volatile int maxIterations = MAX_ITERATIONS;` 与 `public void setMaxIterations(int n)`。
2. 循环条件与「已达最大迭代轮数」提示改用 `maxIterations` 字段。

**验证：** `mvn -q compile` 通过；现有 Smoke11/Smoke12 语义不变。

## T6: PermissionEngine.forSubAgent

**文件：** `permission/PermissionEngine.java`
**依赖：** 无
**步骤：** 加 `public PermissionEngine forSubAgent(Supplier<PermissionMode> modeSupplier)`，复用 blacklist/sandbox/rules/localRulesPath，prompter 传 null（非交互）。

**验证：** `mvn -q compile` 通过。

## T7: SubAgentRunner（构造 + 跑到底排空）

**文件：** `subagent/SubAgentRunner.java`
**依赖：** T1, T3, T5, T6
**步骤：**
1. record `Outcome(output, error, ok, toolCount, seconds, inputTokens, outputTokens)`。
2. 构造器注入父 client/registry/protocol/providerConfig/permissionEngine/parentModeSupplier；setter：modelResolver、progressListener。
3. `buildAgent(registry, client, mode)`：forSubAgent + 全新 ContextManager + setMaxIterations(spec.maxTurns 或默认)。
4. `selectClient(specModel, overrideModel)`：override → specModel → `null`/`inherit`/空 → 父 client；否则 modelResolver 兜底父 client。
5. `run(agent, history, systemPrompt, planMode, description, specName, timeoutSeconds)`：排空队列，累积输出/计数/token，超时看门狗返回超时 Outcome（保留 continuation 供 T8 续跑）。

**验证：** `mvn -q compile` 通过；Smoke13 用假 LlmClient 驱动一个子 Agent 完成一次 LoopComplete。

## T8: SubAgentTaskManager（任务表 + 通知 + 取消 + 看门狗）

**文件：** `subagent/SubAgentTaskManager.java`
**依赖：** T7
**步骤：**
1. `TaskStatus`、`Task`、`TaskNotification` record；`LinkedHashMap` 任务表 + 通知列表。
2. `createTask/setRunning/setCompleted/setFailed/cancelTask/drainNotifications/getTask/listTasks`。
3. `spawnBackground(taskName, work, timeoutSeconds)`：虚拟线程跑 `work`，按 Outcome `setCompleted`/`setFailed`；看门狗到点打断并 `setFailed`("超时")。
4. `continueInBackground(taskName, continuation)`：续跑前台超时的队列，跑完 `setCompleted`。

**验证：** `mvn -q compile` 通过；Smoke13 验证状态迁移（PENDING→RUNNING→COMPLETED/FAILED/CANCELLED）、通知排空、看门狗超时。

## T9: AgentTool（统一工具 + 分流）

**文件：** `subagent/AgentTool.java`
**依赖：** T1, T2, T3, T7, T8
**步骤：**
1. `implements Tool`，`name()="Agent"`、`category()=COMMAND`、`description()` 列角色、`schema()` 生成 `subagent_type` 枚举 + `description/prompt/model/run_in_background/timeout_ms`。
2. setter 注入（agentSpecs/modelResolver/taskManager/permissionEngine/parentModeSupplier/parentHistory/parentSystemPrompt/progressListener/forkDisabled）+ `cloneWithQuerySource`。
3. `execute`：校验 → 分流（无 type=Fork 强制后台 / 有 type=定义式同步或后台）→ 超时转后台 → 返回内联结果或 task id。
4. Fork：`cloneForFork` + 复制父历史 + fork 引导词 + 嵌套拦截（querySource 检查 + 历史扫标签）。

**验证：** `mvn -q compile` 通过；Smoke13 验证 schema、缺参报错、未知类型报错、fork 嵌套拦截、分流结果。

## T10: ChatModel 接线

**文件：** `ui/ChatModel.java`
**依赖：** T9
**步骤：**
1. 构造器末尾：`AgentLoader.loadAll(projectRoot)` 得 specs；建 `SubAgentRunner` + `SubAgentTaskManager`；建 `AgentTool` 注入父 client/registry/protocol/config/permissionEngine/`() -> currentMode`/`() -> history`/`() -> systemPrompt`/modelResolver；`toolRegistry.register(agentTool)`。
2. modelResolver：克隆 `ProviderConfig` 设 `model=别名`，`LlmClient.create(克隆, null)`。
3. `pollAgent` 的 `LoopComplete` 分支：`taskManager.drainNotifications()`，逐条 `msgs.add(tool 提示)` + `history.add(<system-reminder> 后台任务 X 完成/失败) `（不触发新轮）。

**验证：** `mvn -q compile` 通过；`mvn -q package` 产出可运行 jar。

## T11: Smoke13 + 全量验证

**文件：** `target/smoke/Smoke13.java`
**依赖：** 全部
**步骤：**
1. Part A：AgentLoader（临时目录 + 覆盖 + 坏文件）。
2. Part B：ToolFilter（全局禁止/黑名单/白名单/后台白名单/全新实例）。
3. Part C：SubAgentTaskManager（状态迁移 + 通知 + 取消 + 看门狗）。
4. Part D：AgentTool（schema/缺参/未知类型/fork 嵌套拦截，用假 LlmClient）。
5. 编译并运行，全 PASS；`mvn -q package` 成功。

**验证：** Smoke13 输出 `PASS=n FAIL=0`；jar 打包成功。

## 执行顺序

```
T1 → T2 → T3 → T4
       ↘       ↘
T5 → T6 → T7 → T8 → T9 → T10 → T11
```
