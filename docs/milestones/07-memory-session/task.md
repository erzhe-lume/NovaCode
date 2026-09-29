# 记忆与持久化 Tasks（第 9 章）

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/memory/InstructionLoader.java` | 三层指令 + @include |
| 新建 | `src/main/java/com/novacode/session/SessionStore.java` | 会话 JSONL 存档/恢复/清理 |
| 新建 | `src/main/java/com/novacode/memory/MemoryManager.java` | 笔记 + 索引 + 异步提取 |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | onNaturalStop 回调 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 三套机制接入 + /new + /sessions |

## T1: InstructionLoader 三层加载 + @include

**文件：** `memory/InstructionLoader.java`
**依赖：** 无
**步骤：**
1. 定义 `MAX_INCLUDE_DEPTH = 5`。
2. 实现 `loadInstructions(Path projectRoot)`：按序读 `projectRoot/AGENTS.md`、`projectRoot/.mewcode/AGENTS.md`、`~/.mewcode/AGENTS.md`，每个经 `expandIncludes` 展开，加 `Contents of <path>:` 标签，`---` 分隔；全空返回 `""`。
3. 实现 `expandIncludes(content, baseDir, seen, depth)`：逐行，``` 边界切换 code 态；code 态外命中 `parseInclude` 则解析→resolve→护栏检查→递归展开，命中 seen 或超深则保留原行。
4. 实现 `parseInclude`：`@./`、`@../`、`@~/`、`@/` 前缀，其余（含 `@@`、`@name`、含空白）返回 null。
5. 实现 `resolveInclude`：`~/` 展开 home，绝对路径原样，其余 `baseDir` 拼接。
6. 护栏：解析绝对路径后，必须 `startsWith(projectRoot)` 或 `startsWith(~/.mewcode/)`，否则返回 null（拦截）。
**验证：** `mvn -q compile` 通过。

## T2: SessionStore 存档 + ID + 序列化

**文件：** `session/SessionStore.java`
**依赖：** T1（无关，仅同批）
**步骤：**
1. 构造器取 `projectRoot/.mewcode/sessions`，建目录。
2. `newId()`：`yyyyMMdd-HHmmss` + `-` + 2 字节 SecureRandom hex。
3. `startNewSession()`、`currentId()`、`sync(history)`（只追加 `[writtenCount,size)`，更新计数）。
4. 私有 `append(ChatMessage)`：Jackson `Map` 序列化 role/content/tool_calls/tool_call_id/ts，空字段省略，`Files.writeString(..., APPEND)`。
5. `loadSession(Path)` 反序列化：role 小写→枚举；有 `tool_calls` 构造带工具的 ASSISTANT；有 `tool_call_id` 构造 `ChatMessage.toolResult`；坏行 catch 跳过。
6. 缺口截断：先收集所有 TOOL 的 toolCallId，再找首个含「无对应结果」toolCall 的 ASSISTANT，截断到它之前。
7. `resumeMostRecent()`：扫 `*.jsonl` 取 mtime 最新，加载，返回 `ResumeResult`。
8. `cleanExpired()`（30 天 mtime）、`list()`（扫 JSONL 现算 id/首条 user 消息/消息数）。
**验证：** `mvn -q compile` 通过；后续 smoke 测序列化往返。

## T3: MemoryManager 索引注入 + 笔记写

**文件：** `memory/MemoryManager.java`
**依赖：** 无（LLM 提取见 T4）
**步骤：**
1. 构造器：`projectRoot/.mewcode/memory`、`~/.mewcode/memory` 建目录；`noteClient = LlmClient.create(config, null)`。
2. `buildMemorySection()`：读两份 MEMORY.md，`truncateEntrypointContent` 截 200 行/25KB，拼 `# auto memory` + 两个 `## …` 节。
3. 私有 `truncateEntrypointContent`：先按行截，再按 UTF-8 字节回退到换行处，超限附 `> WARNING`。
4. 私有 `writeMemoryFile(dir, name, type, description, body)`：写 frontmatter + body，追 MEMORY.md 指针（含 filename 不重复追加）。
5. 私有 `scanExistingMemories()`：扫两目录 frontmatter 拼 manifest（去重用）。
**验证：** `mvn -q compile` 通过。

## T4: MemoryManager 异步 LLM 提取

**文件：** `memory/MemoryManager.java`（同 T3）
**依赖：** T3
**步骤：**
1. `extractAsync(history)`：`new ArrayList<>(history)` 快照，`Thread.startVirtualThread` 执行 `extract(snapshot)`，异常静默。
2. `extract(history)`：<4 条返回；取最近 40 条拼转录；拼系统提示词（MEMORY_NAME/TYPE/DESC/BODY + `---` + NONE + 四类说明）+ 用户消息（转录 + manifest）。
3. 流式调用 `noteClient.stream(...)`，收集 TextDelta 到 StreamEnd/Error。
4. 按 `---` 分块，正则抽四个字段；type 非法归 `reference`；按 USER_TYPES/PROJECT_TYPES 落目录写文件。
**验证：** `mvn -q compile` 通过。

## T5: Agent 自然结束回调

**文件：** `agent/Agent.java`
**依赖：** 无
**步骤：**
1. 加 `private volatile Consumer<List<ChatMessage>> onNaturalStop;` + `setOnNaturalStop(...)`。
2. `agentLoop` 自然结束分支（`calls.isEmpty()`）里，在 `history.add(ASSISTANT)` 与 emit LoopComplete 之后、return 之前：取回调，非空则 `onNaturalStop.accept(history)`。
**验证：** `mvn -q compile` 通过。

## T6: ChatModel 集成

**文件：** `ui/ChatModel.java`
**依赖：** T1–T5
**步骤：**
1. 加字段 `sessionStore`、`memoryManager`。
2. 构造器：建两个组件；`sessionStore.cleanExpired()`；`instructions = InstructionLoader.loadInstructions(root)`、`memorySection = memoryManager.buildMemorySection()`；system prompt 改 `new BuildOptions(null, instructions, memorySection)`；`resumeMostRecent()` 恢复 history 并回填 msgs、插时间跨度提醒、压一次；`agent.setOnNaturalStop(memoryManager::extractAsync)`。
3. `submit()` 正常消息路径：`history.add(USER)` 后 `sessionStore.sync(history)`。
4. `pollAgent()` 的 `LoopComplete` 分支：`sessionStore.sync(history)`。
5. 新增 `/new` 命令（清 history/msgs、`startNewSession()`）、`/sessions` 命令（`list()` 输出到 msgs）。
6. `/exit` 与 Ctrl+C 退出路径：退出前 `sessionStore.sync(history)`。
**验证：** `mvn -q compile` 通过；`mvn -q package` 产出 jar。

## 执行顺序

```
T1 ─┬─ T2 ─┐
    │       ├─ T5 ─ T6
    └─ T3 ─ T4 ─┘
```

T1/T2/T3 可并行；T4 依赖 T3；T6 依赖全部。
