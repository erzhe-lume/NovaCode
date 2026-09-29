# 命令注册与分发机制 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/command/CommandType.java` | 三类执行模式枚举 |
| 新建 | `src/main/java/com/novacode/command/CommandSpec.java` | 命令元数据记录 |
| 新建 | `src/main/java/com/novacode/command/CommandHandler.java` | 处理函数式接口 |
| 新建 | `src/main/java/com/novacode/command/CommandContext.java` | 界面控制接口 + TokenStats |
| 新建 | `src/main/java/com/novacode/command/CommandParser.java` | 斜杠解析 |
| 新建 | `src/main/java/com/novacode/command/CommandRegistry.java` | 注册 + 冲突检测 + 查找 + 补全 |
| 新建 | `src/main/java/com/novacode/command/BuiltinCommands.java` | 内置命令装配 |
| 修改 | `src/main/java/com/novacode/context/ContextManager.java` | 暴露只读上下文估算 |
| 修改 | `src/main/java/com/novacode/permission/RuleEngine.java` | 暴露只读规则列表 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 实现 CommandContext、接入分流器、Tab 补全 |

## T1: 类型层（CommandType / CommandSpec / CommandHandler / CommandContext / TokenStats）

**文件：** `command/CommandType.java`、`CommandSpec.java`、`CommandHandler.java`、`CommandContext.java`
**依赖：** 无
**步骤：**
1. 定义枚举 `CommandType { LOCAL, UI_STATE, PROMPT }`。
2. 定义 `record CommandSpec(String name, List<String> aliases, String description, String usage, CommandType type, String paramHint, boolean hidden, CommandHandler handler)`；提供便捷工厂 `CommandSpec.of(...)` 便于 `varargs` 传别名。
3. 定义 `@FunctionalInterface CommandHandler { void handle(CommandContext ctx, String args); }`。
4. 定义接口 `CommandContext`：`display`、`sendPrompt`、`setMode(PermissionMode)`、`tokens()`、`refreshStatus()`、`quit()`。
5. 定义 `record TokenStats(int inputTokens, int outputTokens, int cacheRead, int cacheWrite, long contextTokens, int contextWindow)`。

**验证：** `mvn -q compile` 通过，五个类型可被引用。

## T2: 解析器（CommandParser）

**文件：** `command/CommandParser.java`
**依赖：** T1（无，仅用 JDK）
**步骤：**
1. 定义 `record Parsed(String name, String args)`。
2. 定义静态 `Optional<Parsed> parse(String input)`：
   - 输入为 null / 空白 → 空。
   - 不以 `/` 开头 → 空。
   - 去掉前导 `/`，取第一个空格为界：命令名 = 空格前，参数 = 空格后（去首尾空白）。
   - 命令名转小写；命令名为空（只有 `/`）→ 空。

**验证：** 临时 `main` 打印 `parse("/Plan do the thing")` → `name=plan, args=do the thing`；`parse("hello")` → 空。

## T3: 注册中心（CommandRegistry）

**文件：** `command/CommandRegistry.java`
**依赖：** T1
**步骤：**
1. 字段：`Map<String, CommandSpec> byName`（小写键）、`List<CommandSpec> order`（注册顺序）。
2. `register(CommandSpec spec)`：先对主名与每个别名做冲突检测（键已存在即抛 `IllegalStateException`，消息含冲突键），再分别 `byName.put`；`order.add(spec)`。
3. `lookup(String name)`：`byName.get(name.toLowerCase())`。
4. `visible()`：`order` 里 `!hidden()` 的副本。
5. `complete(String prefix)`：遍历 `byName` 的键（小写），`!hidden` 且 `startsWith(prefix)`，返回去重后的排序列表。

**验证：** 临时 `main` 注册 `/plan` + 别名 `/p`，再注册 `/p` 抛异常；`complete("pl")` 返回 `["plan"]`。

## T4: 支持性 getter（ContextManager / RuleEngine）

**文件：** `context/ContextManager.java`、`permission/RuleEngine.java`
**依赖：** 无
**步骤：**
1. `ContextManager` 增加 `public long estimateCurrent(List<ChatMessage> history)`：返回 `estimator.estimateTotal(TokenEstimator.totalChars(history))`。
2. `RuleEngine` 增加 `public List<Rule> allRules()`：把三层规则展平返回（带 `source` 字段标明来源 tier）。

**验证：** `mvn -q compile` 通过。

## T5: 内置命令装配（BuiltinCommands）

**文件：** `command/BuiltinCommands.java`
**依赖：** T1–T4
**步骤：**
1. 定义 `static CommandRegistry build(CommandContext ctx, ContextManager cm, SessionStore ss, MemoryManager mm, PermissionConfig pc, ProviderConfig cfg)`。
2. 按 plan 表格注册 13 条命令，每条填 name / aliases / description / usage / type / paramHint / hidden / handler。
3. `/help`：遍历 `registry.visible()`，逐条 `ctx.display(usage + " — " + description)`；需持有 registry 引用（用数组或闭包先建 registry 再注册 /help）。
4. `/status`：用 `ctx.tokens()` 读累计 Token，用 `cm.estimateCurrent(history)` 读上下文（history 由 `ChatModel` 经 `CommandContext` 间接提供——见 T6 接口补充：`tokens()` 已含 contextTokens，故此处只用 `ctx.tokens()` 与 `cm` 拿窗口值即可，实际实现以 ctx 为准）。
5. `/session`：`ss.list()` 逐条 `ctx.display`；`/memory`：`mm.buildMemorySection()` 分段 `ctx.display`；`/permission`：`pc.engine().allRules()` + `currentMode` 逐条 `ctx.display`。
6. `/do`、`/review`：`ctx.sendPrompt(...)`；`/plan`：`ctx.setMode(PLAN)` + `ctx.display(...)`；`/exit`：`ctx.quit()`。

**验证：** `mvn -q compile` 通过；无名称 / 别名冲突（启动不抛异常）。

## T6: 接入 ChatModel（分流器 + 界面控制实现 + Tab 补全）

**文件：** `ui/ChatModel.java`
**依赖：** T1–T5
**步骤：**
1. `ChatModel implements Model, PermissionPrompter, CommandContext`。
2. 新增字段：`CommandRegistry commands`、`PermissionConfig permissionConfig`（原 `permConfig` 由局部改字段，供 `/permission`）、`boolean quitRequested`、`boolean promptPending`。
3. 实现 `CommandContext` 六个方法：
   - `display(s)`：`msgs.add(new Msg("tool", s, false))`。
   - `sendPrompt(s)`：`msgs.add(new Msg("user", s, false))`；`history.add(USER, s)`；`sessionStore.sync(history)`；`promptPending = true`。
   - `setMode(m)`：`currentMode = m`。
   - `tokens()`：组装 `TokenStats(totalInputTokens, totalOutputTokens, totalCacheRead, totalCacheWrite, contextManager.estimateCurrent(history), config.getContextWindow())`。
   - `refreshStatus()`：空实现（TEA 每帧自动重绘）。
   - `quit()`：`sessionStore.sync(history)`；`quitRequested = true`。
4. 构造函数末尾：`this.permissionConfig = permConfig`；`this.commands = BuiltinCommands.build(this, contextManager, sessionStore, memoryManager, permissionConfig, config)`。
5. 重写 `submit()`：空 / streaming 早返回后，`CommandParser.parse(text)` 命中 → `commands.lookup`；未命中 → `msgs.add(tool "未知命令 … 用 /help 查看")`；命中 → `handler.handle(this, args)`。收尾：`quitRequested` → 返回 QuitMessage；`promptPending` → 置 false 后返回 `startAgent()`；否则清输入返回 `UpdateResult.from(this)`。非斜杠走原普通消息路径。
6. `handleKey` 增加 `case "tab"`：输入以 `/` 开头时 `complete` 前缀，唯一补全（追加空格），多候选 `msgs.add` 显示候选列表；否则忽略。
7. 删除 submit 里原硬编码的 `/exit` `/plan` `/do` `/compact` `/new` `/sessions` if 分支（逻辑已迁入 BuiltinCommands）。

**验证：** `mvn -q compile` 通过；`mvn -q package` 产出 jar。

## 执行顺序

```
T1 → T2 → T3
              ↘
T4 ──────────→ T5 → T6
```
