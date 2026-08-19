# Hook 系统 Tasks（第 12 章）

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/hook/HookEvent.java` | 9 事件枚举 + `fromString` |
| 新建 | `src/main/java/com/novacode/hook/HookActionType.java` | 4 动作类型枚举 |
| 新建 | `src/main/java/com/novacode/hook/HookAction.java` | 动作 record（按 type 分用字段） |
| 新建 | `src/main/java/com/novacode/hook/Hook.java` | 单条 hook record |
| 新建 | `src/main/java/com/novacode/hook/HookContext.java` | 事件上下文 + `expand` 模板替换 |
| 新建 | `src/main/java/com/novacode/hook/HookResult.java` | 动作结果 record |
| 新建 | `src/main/java/com/novacode/hook/PreToolResult.java` | 拦截结果 record |
| 新建 | `src/main/java/com/novacode/hook/HookCondition.java` | 条件求值（`==`/`!=`/`=~`/`=*`、`&&`/`||` 不混用） |
| 新建 | `src/main/java/com/novacode/hook/HookEngine.java` | 引擎核心：校验、分发、执行 |
| 新建 | `src/main/java/com/novacode/hook/HookConfig.java` | YAML POJO（`@JsonProperty("if")`） |
| 新建 | `src/main/java/com/novacode/hook/HookLoader.java` | 两级 YAML 加载 + 集中校验 |
| 修改 | `src/main/java/com/novacode/permission/Rule.java` | `globToRegex` 改 public |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | hookEngine 字段 + 生命周期触发 + 工具前拦截 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 建引擎 + 会话/退出事件 + 会话注入 |
| 新建 | `target/smoke/Smoke12.java` | 无头冒烟 |

## T1: 数据骨架（enum + record）

**文件：** `hook/HookEvent.java`、`HookActionType.java`、`HookAction.java`、`Hook.java`、`HookResult.java`、`PreToolResult.java`
**依赖：** 无
**步骤：**
1. `HookEvent`：9 个枚举常量（值字符串），`value()` 访问器，`fromString(String)` 未知返回 null。
2. `HookActionType`：COMMAND/PROMPT/HTTP/AGENT，`fromString`。
3. `HookAction` record（8 字段）+ 便捷构造 `(type, command, message)`。
4. `Hook` record（id/event/condition/action/reject/once/async）。
5. `HookResult`（hookId/output/success/reject）、`PreToolResult`（rejected/message）。

**验证：** `$JAVA_HOME/bin/javac -d target/classes` 编译这 6 个文件无错（或先 `mvn -q -o compile` 待接线后统一验）。

## T2: HookContext 模板替换

**文件：** `hook/HookContext.java`
**依赖：** T1
**步骤：**
1. record（event/toolName/toolArgs/filePath/message/error）。
2. `expand(String)`：替换 `${event}` `${tool}` `${file_path}` `${message}` `${error}` `${args.<字段>}`；无 `${` 原样返回。

**验证：** 临时 main 或 Smoke 断言 `expand("tool=${tool} path=${file_path}")` 结果正确。

## T3: HookCondition 条件求值

**文件：** `hook/HookCondition.java`
**依赖：** T1、T2；`Rule.globToRegex` 改 public（见 T6）
**步骤：**
1. `evaluate(String cond, HookContext ctx)`：strip 后空返回 true；按 `&&`/`||` 单操作符切分（`splitComposite`），左到右求值组合。
2. 叶子 `evaluateLeaf`：按 `!=`/`=~`/`=*`/`==` 顺序探测；`==`→equals、`!=`→!equals、`=~`→`find()`（去 `/` 分隔）、`=*`→`Rule.globToRegex(..., pathSemantics).matches()`（去引号）。
3. `resolveVar`：`tool`/`event`/`file_path`/`message`/`args.xxx`。
4. `mixesOperators(String)`：同时含 `&&` 与 `||` 返回 true。
5. 裸变量（无操作符）→ 非空为真。

**验证：** Smoke 断言 `tool == "Bash"`、`tool != "Read"`、`event =~ /^pre_/`、`file_path =* "src/*.go"`、`a && b`、`a || b` 各自正确；`mixesOperators("a && b || c")` 为 true。

## T4: HookEngine 核心

**文件：** `hook/HookEngine.java`
**依赖：** T1、T2、T3
**步骤：**
1. 状态：`List<Hook> hooks`（同步）、`Set<String> fired`（同步）、`List<HookResult> notifications`。
2. `addHook` / `loadHooks`（清 fired）/ `snapshotHooks` / `drainNotifications`。
3. `static validate(List<Hook>)`：聚合错误（event 非法、action.type 缺失、command 缺 command、prompt 缺 message、http 缺合法 http(s) url、agent 缺 message+command、timeout 负、**pre_tool_use+async**、**mixesOperators**）。
4. `runHooks(ctx)`：按事件匹配 + `shouldFire`（条件 + once 去重）；`async` → `CompletableFuture.runAsync` 执行并记 notification，返回 `(async)` 占位；否则同步执行。
5. `runPreToolHooks(toolName, args)`：仅 PRE_TOOL_USE；命中 reject 返回 `PreToolResult(true, reason)`（reason=输出，空则默认 `blocked by hook <id>`）。
6. `executeAction` 分发：`executeCommand`（bash -c + NOVACODE_* 环境变量 + timeout destroyForcibly）、`PROMPT` 直接产出、`executeHTTP`（HttpClient 默认 POST/JSON、无 body 自动生成、64KB 截断）、`executeAgent`（无 runner 返回明确错误）。
7. `setAgentRunner(BiFunction<...>)` 可选（占位用）。

**验证：** Smoke 断言：validate 聚合错误；reject 拦截；once 二次为空；async 非阻塞；超时终止；agent 无 runner 报错。

## T5: 配置加载 HookConfig + HookLoader

**文件：** `hook/HookConfig.java`、`hook/HookLoader.java`
**依赖：** T1、T4
**步骤：**
1. `HookConfig` POJO：id/event/condition（`@JsonProperty("if")`）/reject/once/async + 嵌套 `ActionConfig`（type/command/message/url/method/headers/body/timeout）；转 `Hook` 的 `toHook()`。
2. `HookLoader.load(projectRoot, userHome)`：读 `userHome/.novacode/hooks.yaml` + `projectRoot/.novacode/hooks.yaml`（缺失→空），`List<HookConfig>` → `List<Hook>`（跳过坏条目），返回 `LoadedHooks(hooks, errors)`（errors 含 validate 结果 + YAML 解析错误）。
3. `LoadedHooks` 小 record。

**验证：** Smoke 断言：两级加载、缺文件空、非法条目跳过且记 errors、`if` 键正确映射 condition。

## T6: Rule.globToRegex 改 public

**文件：** `permission/Rule.java`
**依赖：** T3 需要它
**步骤：**
1. `static Pattern globToRegex(...)` 由包私有改为 `public static`（注释补一句「第 12 章 Hook 条件复用」）。

**验证：** `mvn -q -o compile` 无错。

## T7: Agent 接线

**文件：** `agent/Agent.java`
**依赖：** T4、T6
**步骤：**
1. 字段 `private HookEngine hookEngine`（可空）+ `setHookEngine(HookEngine)`。
2. 私有 `fire(HookContext)`：`hookEngine==null` 返回空列表；否则 `runHooks`。
3. `agentLoop`：for 循环前 `turn_start` 触发，收集 prompt 输出注入 `<system-reminder>` 用户消息（记录列表，finally 移除）；`finally` 触发 `turn_end`。
4. `streamOnce`：`client.stream` 前发 `pre_send`；`StreamEnd` 分支发 `post_receive`。
5. `executeBatched`：读批量每个 + 串行每个，执行前 `runPreToolHooks`；rejected → 该调用结果 = `"Error: Hook 拦截: <reason>"`（补发 ToolResultEvent，不执行工具）；执行后发 `post_tool_use`。
6. 非 prompt 动作输出经 `Notice` 事件回显（可选：post_tool_use 的 command/http 输出）。

**验证：** Smoke：FakeClient 快照历史含 `<system-reminder>`；pre_tool_use reject 使工具不执行且历史含拒绝原因。

## T8: ChatModel 接线

**文件：** `ui/ChatModel.java`
**依赖：** T4、T5、T7
**步骤：**
1. 字段 `HookEngine hookEngine`、`List<String> sessionHookPrompts`。
2. 构造器（skillManager 之后）：`HookLoader.load` → `hookEngine.loadHooks` → `setHookEngine(agent)` → 校验错误打印 stderr；发 `session_start`（首会话）。
3. `buildCurrentSystemPrompt()`：追加「会话注入」段（`sessionHookPrompts` 非空时）。
4. `newSession()`：清空前发 `session_end`，`startNewSession` 后发 `session_start`，清空 `sessionHookPrompts`。
5. 退出路径（`quit()` 或 QuitMessage 前）发 `shutdown`。

**验证：** `mvn -q -o compile` 无错；Smoke（如有 UI 依赖则跳过，改为验证 Agent 侧 + 引擎侧）。

## T9: Smoke12 无头冒烟

**文件：** `target/smoke/Smoke12.java`
**依赖：** T1–T8
**步骤：**
1. 覆盖：条件四操作符 + 组合不混用；validate 聚合；reject 拦截回灌；once；async；timeout；agent 占位；HookLoader 两级加载 + 降级；`if` 映射；Agent 的 turn_start 注入 + pre_tool_use 拦截（FakeClient 快照）。
2. 用 `$JAVA_HOME/bin/javac -cp "target/classes;$(cat target/smoke/cp.txt)"` 编译到 `target/smoke/`，`$JAVA_HOME/bin/java` 运行，断言 PASS/FAIL。

**验证：** 输出 `PASS=N FAIL=0`。

## T10: 全量编译 + jar

**依赖：** T1–T9
**步骤：**
1. `mvn -q -o compile` 无警告。
2. `mvn -q -o package` 成功，`unzip -l` 确认 `com/novacode/hook/*.class` 在 shaded jar。

**验证：** 两者均退出码 0。

## 执行顺序

```
T1 → T2 → T3 → T4 → T5 → T7 → T8 → T9 → T10
              ↘ T6（T3 前置）↗
```
