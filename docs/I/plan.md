# Hook 系统 Plan（第 12 章）

> 基于已批准 spec.md。实现语言 Java 21，`-Xlint:all` 无警告，沿用项目既有风格（record、text block、无多余注释）。

## 架构概览

新增 `com.novacode.hook` 包（Hook 引擎核心，与 provider 无关），并在 `Agent` / `ChatModel` 两个编排层接线。核心类是一个**独立、可单测**的 `HookEngine`：接收 `HookContext`，按事件名匹配注册的 hook，求值条件，执行动作，返回 `HookResult` 列表（或 `PreToolResult` 拦截结果）。引擎不持有 Agent / UI 依赖，动作执行失败只记录、不抛出。

```
生命周期节点                          HookEngine
─────────────                        ──────────
session_start / session_end  ─┐
turn_start / turn_end        ─┤   runHooks(ctx) ──► List<HookResult>
pre_send / post_receive      ─┤        （条件求值 + once 去重 + 动作执行）
pre_tool_use / post_tool_use ─┤   runPreToolHooks(tool,args) ──► PreToolResult
shutdown                     ─┘        （命中 reject → rejected=true + 拒绝原因）
```

- **引擎核心** `HookEngine`：注册、集中校验、条件求值、动作执行、`once` 去重、`async` 异步、`timeout` 超时。动作类型 COMMAND/PROMPT/HTTP/AGENT（AGENT 占位）。
- **条件求值** `HookCondition`：叶子操作符 `==`/`!=`/`=~`/`=*`，组合 `&&`/`||` 二选一不混用；glob 复用 `Rule.globToRegex`。
- **配置** `HookConfig` + `HookLoader`：`~/.novacode/hooks.yaml`（用户级）+ `.novacode/hooks.yaml`（项目级）声明式加载，集中校验聚合错误。

接线点：
- `Agent`：持有 `HookEngine`（可空，空则 no-op）。`agentLoop` 顶部发 `turn_start`、`finally` 发 `turn_end`；`streamOnce` 发 `pre_send`/`post_receive`；`executeBatched` 每个工具调用前后发 `pre_tool_use`/`post_tool_use`，`pre_tool_use` 命中 reject 时拒绝原因作为工具结果回灌。
- `ChatModel`：持有同一 `HookEngine`。会话创建时发 `session_start`、`/new` 切换前发 `session_end`、退出前发 `shutdown`；`turn_start` 的 prompt 动作输出注入为轮级 `<system-reminder>`。

## 核心数据结构

### 事件与动作类型

```java
public enum HookEvent {
    SHUTDOWN("shutdown"),
    SESSION_START("session_start"), SESSION_END("session_end"),
    TURN_START("turn_start"), TURN_END("turn_end"),
    PRE_SEND("pre_send"), POST_RECEIVE("post_receive"),
    PRE_TOOL_USE("pre_tool_use"), POST_TOOL_USE("post_tool_use");
    public static HookEvent fromString(String s);   // 未知 → null
}

public enum HookActionType { COMMAND("command"), PROMPT("prompt"), HTTP("http"), AGENT("agent"); }
```

### 动作

```java
public record HookAction(HookActionType type, String command, String message,
        String url, String method, Map<String, String> headers, String body, Duration timeout) {}
```
字段按 type 分用：`command`→command；`prompt`→message；`http`→url/method/headers/body；`agent`→message（command 兜底）。

### 单条 hook

```java
public record Hook(String id, HookEvent event, String condition, HookAction action,
        boolean reject, boolean once, boolean async) {}
```
`id` 用于 `once` 去重与日志；`reject` 仅 `pre_tool_use` 有意义；`async` 仅非拦截事件允许。

### 上下文与结果

```java
public record HookContext(HookEvent event, String toolName, Map<String, Object> toolArgs,
        String filePath, String message, String error) {
    String expand(String template);   // ${event}/${tool}/${file_path}/${message}/${error}/${args.xxx}
}
public record HookResult(String hookId, String output, boolean success, boolean reject) {}
public record PreToolResult(boolean rejected, String message) {}
```

## 模块设计

### HookEngine —— 引擎核心
**职责**：hook 注册、集中校验、事件分发、条件求值、动作执行。**对外接口**：
- `addHook(Hook)` / `loadHooks(List<Hook>)`（重载时清空 `once` 去重集）
- `static List<String> validate(List<Hook>)`（聚合所有错误）
- `List<HookResult> runHooks(HookContext ctx)`（非拦截事件，含异步）
- `PreToolResult runPreToolHooks(String toolName, Map<String,Object> args)`（拦截事件，同步）

**校验项**（一次聚合全部，不因首错中断）：event 非法 / action 缺 type / command 缺 command / prompt 缺 message / http 缺合法 url / agent 缺 message+command / timeout 为负 / **`pre_tool_use` 声明 `async`** / **条件混用 `&&` 与 `||`**。

**动作执行**（失败只记录不抛出）：
- `COMMAND`：`ProcessBuilder("bash","-c",cmd)`，注入 `NOVACODE_EVENT`/`NOVACODE_TOOL`/`NOVACODE_FILE_PATH`，模板变量替换，`waitFor(timeout)` 超时 `destroyForcibly`；输出=stdout(+stderr)，`success = exit==0`。
- `PROMPT`：直接产出 message，`success=true`。
- `HTTP`：`java.net.http.HttpClient`，默认 POST + `Content-Type: application/json`；无 body 时自动生成含上下文的 JSON；2xx 为成功；响应体截断 64KB。
- `AGENT`：占位——未注册执行器返回明确错误（`"agent-type hook configured but no AgentRunner registered"`），`success=false`。

### HookCondition —— 条件求值
**职责**：字符串条件 → 布尔。**对外接口**：`static boolean evaluate(String cond, HookContext ctx)`、`static boolean mixesOperators(String cond)`。

叶子操作符（按 `!=`/`=~`/`=*`/`==` 顺序探测，`!=` 须先于 `==`）：
| 操作符 | 语义 | 实现 |
|--------|------|------|
| `==` | 精确相等 | `val.equals(right)` |
| `!=` | 反向不等 | `!val.equals(right)` |
| `=~` | 正则（部分匹配） | `Pattern.compile(stripSlash(right)).matcher(val).find()` |
| `=*` | glob（全串匹配） | `Rule.globToRegex(stripQuote(right), pathSemantics).matcher(val).matches()` |

变量：`event`/`tool`/`file_path`/`message`/`args.<字段>`；裸变量 = 非空为真。`pathSemantics` 仅 `file_path` 为 true。组合：按 `&&` 或 `||` 单一操作符切分，左到右求值；`mixesOperators` 检测到同时含 `&&` 与 `||` 时返回 true（校验报错）。

### HookConfig / HookLoader —— 声明式加载
**职责**：YAML 加载 + 集中校验。**对外接口**：`HookLoader.load(Path projectRoot, Path userHome) -> LoadedHooks`（含 `hooks` + `errors`）。

```yaml
# ~/.novacode/hooks.yaml  /  .novacode/hooks.yaml
hooks:
  - id: block-rm
    event: pre_tool_use
    if: 'tool == "Bash" && args.command =~ /rm -rf/'
    action: { type: prompt, message: "危险命令已拦截" }
    reject: true
  - id: fmt
    event: post_tool_use
    action: { type: command, command: "gofmt -w ${file_path}", timeout: 30 }
  - id: slack
    event: turn_end
    action: { type: http, url: "https://hooks.slack.com/...", method: POST }
```
`HookConfig` 为 Jackson POJO（`@JsonProperty("if")` 映射 `condition`；嵌套 `action` 用 `ActionConfig`）。缺失文件 → 空；非法 YAML / 校验失败 → 该条跳过并记入 `errors`，不崩溃。

### Agent（接线）
**新增**：`private HookEngine hookEngine`（可空）+ `setHookEngine(HookEngine)`；私有 `fire(HookContext)` 返回 `List<HookResult>`（空则 no-op）。
- `agentLoop` 顶部：`turn_start` 触发，收集 prompt 动作输出 → 注入为 `<system-reminder>` 用户消息（`finally` 移除）；`finally`：`turn_end` 触发。
- `streamOnce`：`client.stream` 前发 `pre_send`，`StreamEnd` 后发 `post_receive`。
- `executeBatched`：每个工具调用（读批量里的每个 + 串行的每个）执行前 `runPreToolHooks`，命中 reject → 该调用结果 = `"Error: Hook 拦截: <reason>"`，不执行工具、回灌给模型；执行后发 `post_tool_use`。prompt/命令/HTTP 动作输出经 `Notice` 事件回显。

### ChatModel（接线）
**新增**：构造器建 `HookEngine` 并 `HookLoader.load` 后 `loadHooks`，`setHookEngine(agent)`，把校验错误打印到 stderr；持有 `List<String> sessionHookPrompts`。
- 会话创建（`resumeSession` 无历史 + `newSession`）后发 `session_start`；`newSession` 清空前发 `session_end`；退出前发 `shutdown`。
- `session_start` 的 prompt 输出存入 `sessionHookPrompts`，`buildCurrentSystemPrompt()` 追加为「会话注入」段；`newSession` 清空该列表。

## 模块交互

```
ChatModel 构造 ─► HookLoader.load ─► validate ─► HookEngine.loadHooks
     │                                              │ setHookEngine
     │  session_start / session_end / shutdown ◄────┤
     ▼                                              │
Agent.run(agentLoop)
     ├─ turn_start   ─► runHooks ─► prompt 输出注入 <system-reminder>
     ├─ pre_send     ─► runHooks
     ├─ post_receive ─► runHooks
     ├─ executeBatched：每个调用
     │     pre_tool_use ─► runPreToolHooks ─► rejected? → 工具结果=拒绝原因（回灌）
     │     （执行工具）
     │     post_tool_use ─► runHooks
     └─ turn_end     ─► runHooks
```

## 文件组织

```
project/
├── src/main/java/com/novacode/
│   ├── hook/                              # 新增包
│   │   ├── HookEvent.java
│   │   ├── HookActionType.java
│   │   ├── HookAction.java
│   │   ├── Hook.java
│   │   ├── HookContext.java
│   │   ├── HookResult.java
│   │   ├── PreToolResult.java
│   │   ├── HookCondition.java
│   │   ├── HookEngine.java
│   │   ├── HookConfig.java
│   │   └── HookLoader.java
│   ├── permission/Rule.java               # 修改：globToRegex 由包私有改 public（复用）
│   ├── agent/Agent.java                   # 修改：hookEngine 字段 + 生命周期触发 + 工具前拦截
│   └── ui/ChatModel.java                  # 修改：建引擎 + 会话/退出事件 + 会话注入
├── target/smoke/Smoke12.java              # 新增：无头冒烟（独立于生产，验证后）
└── docs/I/{spec,plan,task,checklist}.md   # 本章文档
```

配置文件（运行期）：`~/.novacode/hooks.yaml`（用户级）、`.novacode/hooks.yaml`（项目级）。

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 引擎独立无 UI 依赖 | `HookEngine` 纯 Java，`runHooks` 返回结果、失败只记录 | 可无头单测；满足 N1「失败不中断」 |
| 条件操作符 | `==`/`!=`/`=~`/`=*` 字符串语法 | 复用参考实现 + 权限匹配概念；「精确/反向/正则/glob」一一对应 |
| glob 复用 | `Rule.globToRegex` 改 public 后复用，`file_path` 用路径语义 | spec 明确「复用权限规则的匹配语法」；避免两套 glob 语义 |
| 组合不混用 | `mixesOperators` 检测 `&&` 与 `||` 同现即校验错误 | spec「全部满足/任一满足二选一、不混用」 |
| 拦截不异步 | `pre_tool_use` + `async` 校验报错 | spec「拦截类事件不允许异步」 |
| 失败语义 | 去掉参考实现的 `onError`（fail/reject），失败恒记日志 | spec N1「失败只记日志、绝不中断」；拦截仅靠 `reject` 标志 |
| `if` 字段名 | YAML 键 `if` 经 `@JsonProperty("if")` 映射 `condition` | 遵循用户三要素格式（`if` 是 Java 关键字） |
| 配置位置 | `~/.novacode/hooks.yaml` + `.novacode/hooks.yaml` | 与 permissions.yaml 一致（.novacode 根），「复用权限」主题统一 |
| 注入机制 | `turn_start` prompt → `<system-reminder>` 轮级注入（finally 移除）；`session_start` → 会话注入段入 system prompt | 复用 ch04 plan 提醒注入模式；session 级跨轮、turn 级单轮 |
| AGENT 占位 | 无 `agentRunner` 返回明确错误 | spec「先不做子 Agent 真实运行」 |
| once 范围 | 内存 `Set<String>`，`loadHooks` 清空 | spec「不做持久化」；程序运行期去重 |
| 引擎接入 Agent | 可空字段 + setter（非构造参数） | 不改既有 Smoke 测试的 Agent 构造；空即 no-op 向后兼容 |
