# 记忆与持久化 Plan（第 9 章）

## 架构概览

新增两个包，把「长期记忆」「工作记忆」与「持久化」拆开：

```
com.novacode.memory
├── InstructionLoader.java   三层指令文件 + @include 展开（F1/F2）
└── MemoryManager.java       自动笔记 + 索引注入 + 异步 LLM 提取（F7/F8/F9）

com.novacode.session
└── SessionStore.java        会话 JSONL 追加写 + 恢复 + 清理（F4/F5/F6）
```

三套机制都挂在现有 `ChatModel` 上（构造时初始化、每轮同步），Agent 只新增一个「自然结束」回调，不侵入协议。

- **InstructionLoader**：静态加载器，按优先级从高到低读三个 AGENTS.md，逐层展开 `@include`，返回拼接文本。
- **SessionStore**：持 `sessionsDir`、`currentId`、`writtenCount`；`sync(history)` 只追加自上次同步后新增的消息；`resumeMostRecent()` 扫目录取最新文件并恢复。
- **MemoryManager**：持用户级/项目级两个 memory 目录和一个无工具的 LLM 客户端；`buildMemorySection()` 读两份 MEMORY.md 拼索引；`extractAsync()` 在虚拟线程上做 LLM 提取 + 写文件 + 更新索引。

## 核心数据结构

### InstructionLoader
- `static String loadInstructions(Path projectRoot)` — 三层加载拼接，无文件返回 `""`。
- `static String expandIncludes(String content, String baseDir, Set<String> seen, int depth)` — 递归展开 `@include`。
- `static String parseInclude(String trimmed)` — 识别 `@./`、`@../`、`@~/`、`@/`；其余 `@token` 返回 null。
- `static String resolveInclude(String p, String baseDir)` — `~/` 展开 home，其余按 baseDir 解析。

常量：`MAX_INCLUDE_DEPTH = 5`。

### SessionStore
- `SessionStore(Path projectRoot)` — `sessionsDir = projectRoot/.mewcode/sessions`。
- `static String newId()` — `yyyyMMdd-HHmmss` + `-` + 2 字节 SecureRandom hex。
- `String startNewSession()` — 置 `currentId`、`writtenCount=0`，返回 id。
- `void sync(List<ChatMessage> history)` — 追加 `[writtenCount, size)` 的消息，更新 `writtenCount`。
- `ResumeResult resumeMostRecent()` — 扫最新 `.jsonl`，加载并恢复，返回消息列表 + 最后时间戳。
- `static List<ChatMessage> loadSession(Path file)` — 逐行读，坏行跳过，缺口截断。
- `void cleanExpired()` — 删除 mtime 超 30 天的会话文件。
- `List<SessionInfo> list()` — 扫 JSONL 现算 id/首条消息/消息数（F5）。

记录：`record SessionInfo(String id, String firstMessage, int messageCount, long modTime)`；
`record ResumeResult(boolean found, String id, List<ChatMessage> messages, long lastTimestamp)`。

JSONL 行格式（Jackson `Map` 序列化，空字段省略）：
```json
{"role":"user","content":"…","ts":1786159967}
{"role":"assistant","content":"…","tool_calls":[{"id":"c1","name":"bash","arguments":{…}}],"ts":…}
{"role":"tool","content":"…","tool_call_id":"c1","ts":…}
```

### MemoryManager
- `MemoryManager(Path projectRoot, ProviderConfig config)` — 建两个目录、建无工具 LLM 客户端。
- `String buildMemorySection()` — 读两份 MEMORY.md，拼成 system-reminder 文本（F9）。
- `void extractAsync(List<ChatMessage> history)` — 快照后开虚拟线程调 `extract`（N1）。

常量：`ENTRYPOINT_NAME="MEMORY.md"`、`MAX_ENTRYPOINT_LINES=200`、`MAX_ENTRYPOINT_BYTES=25_000`；
`USER_TYPES={user,feedback}`、`PROJECT_TYPES={project,reference}`。

笔记文件格式（与 mewcode 参考一致）：
```markdown
---
name: <kebab-case>
description: <一行描述>
metadata:
  type: <user|feedback|project|reference>
---

<body>
```

索引格式：`- [name](file.md) — description`（每行一条指针）。

## 模块设计

### InstructionLoader（三层指令 + @include）
**职责：** 读三层 AGENTS.md、展开 `@include`、拼接（F1/F2）。
**对外接口：** `loadInstructions(Path)`。
**依赖：** 文件系统。

加载顺序（高优先级在前）：项目根 `AGENTS.md` → `.mewcode/AGENTS.md` → `~/.mewcode/AGENTS.md`。
每个文件前加 `Contents of <path>:` 标签，文件间用 `---` 分隔。

`@include` 展开规则：
1. 代码块（```）内的 `@include` 不展开。
2. 同一绝对路径只包含一次（visited 集合防循环）。
3. 嵌套深度超过 5 停止展开。
4. 路径护栏：解析后的绝对路径必须在「项目根」或「`~/.mewcode/`」之内，否则拦截（保留原始行）。

### SessionStore（会话存档）
**职责：** JSONL 追加写、恢复、清理、列表（F4/F5/F6）。
**对外接口：** 见上。
**依赖：** Jackson、文件系统、`ChatMessage`。

恢复流程（`loadSession`）：
1. 逐行读，空行跳过，坏 JSON 行跳过（F6 坏行）。
2. 反序列化为 `ChatMessage`（role / content / tool_calls / tool_call_id）。
3. 缺口截断：存在「有 toolCall id 但没有对应 TOOL 结果」的 ASSISTANT 消息时，从该消息之前截断（F6 孤儿调用）。
4. 返回恢复后的列表。

`resumeMostRecent()`：扫 `sessions/*.jsonl` 按 mtime 取最新，加载并返回；无文件或空则 `found=false`。

`cleanExpired()`：mtime 早于 30 天前的文件删除。

### MemoryManager（自动笔记 + 索引注入）
**职责：** 记忆索引注入、异步 LLM 提取、写笔记、更新索引（F7/F8/F9）。
**对外接口：** 见上。
**依赖：** Jackson、`LlmClient`、`ProviderConfig`、文件系统。

`buildMemorySection()`：读用户级与项目级 MEMORY.md，各截断到 200 行 / 25KB（超限附警告），拼成：
```
# auto memory
## User-level MEMORY.md (`~/.mewcode/memory/MEMORY.md`)
<内容>
## Project-level MEMORY.md (`<project>/.mewcode/memory/MEMORY.md`)
<内容>
```

`extractAsync(history)`：快照历史 → 虚拟线程执行 `extract`。`extract` 步骤：
1. 历史不足 4 条直接返回。
2. 取最近 40 条拼 `[role]: content` 转录；扫已有笔记拼 manifest（`- [type] filename: description`）。
3. 用无工具 LLM 客户端发一次流式调用：系统提示词要求按 `MEMORY_NAME / MEMORY_TYPE / MEMORY_DESC / MEMORY_BODY` 分块输出、用 `---` 分隔、无值得记的输出 `NONE`；用户消息放转录 + manifest（去重依据）。
4. 解析输出，逐块写 `.md`（同名覆盖=更新），追加 MEMORY.md 指针（已存在文件名不重复追加）。

## 模块交互

```
ChatModel 构造：
    InstructionLoader.loadInstructions(root)        → customInstructions
    MemoryManager.buildMemorySection()              → memorySection
    PromptBuilder.buildSystemPrompt(env, BuildOptions(null, instructions, memory))
    SessionStore.resumeMostRecent()                  → history 恢复 + 时间跨度提醒 + 压一次
    agent.setOnNaturalStop(memoryManager::extractAsync)

用户 submit：history.add(USER) → SessionStore.sync(history) → startAgent
Agent 自然结束：agentLoop 里 calls.isEmpty() → history.add(ASSISTANT) → onNaturalStop(history)
ChatModel pollAgent LoopComplete：SessionStore.sync(history)
/exit 或 Ctrl+C：SessionStore.sync(history)
```

时间跨度提醒（F6）：`now - lastTimestamp > 3600s` 时，在恢复的 history 头部插入一条 USER 消息，说明距上次会话已隔多久、本会话是延续。

token 超限压一次（F6）：恢复后调用一次 `contextManager.compressNow(history)`；未近窗口上限则自然 no-op。

## 文件组织

```
src/main/java/com/novacode/
├── memory/
│   ├── InstructionLoader.java   新
│   └── MemoryManager.java       新
├── session/
│   └── SessionStore.java        新
├── agent/
│   └── Agent.java               改：新增 onNaturalStop 回调，自然结束时触发
└── ui/
    └── ChatModel.java           改：初始化三套机制、注入指令+记忆、会话续写与恢复、/new、/sessions
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 会话/记忆放哪个目录 | `.mewcode/sessions`、`.mewcode/memory`、`~/.mewcode/memory` | 与 mewcode 参考一致，且与指令文件层 `.mewcode/` 同根；spec 里「sessions/memory 目录」按参考落位 |
| 指令文件名 | 三处都用 `AGENTS.md` | 跨工具通用约定；spec 未指定，可改 MEWCODE.md |
| 指令优先级顺序 | 项目根 → `.mewcode/` → `~/.mewcode/`（高在前） | 按 spec「高优先级排前面」；参考实现是低在前（后出现更受关注），此处以 spec 文字为准 |
| @include 路径护栏 | 目标必须落在「项目根」或「`~/.mewcode/`」内 | spec 要求「拦截跳出项目目录」；`~/.mewcode/` 是受信用户配置层，一并放行 |
| 会话 JSONL 字段 | `role/content/tool_calls/tool_call_id/ts`，空字段省略 | 直接序列化 `ChatMessage` 的 OpenAI 式工具结构；`ts` 供时间跨度提醒 |
| 工具无结果截断判定 | 首个含「无对应 TOOL 结果」的 ASSISTANT 消息处截断 | 回放孤儿 tool_calls 会协议非法；截断到缺口前最安全 |
| 记忆注入位置 | System Prompt 末尾（memorySection 槽） | 复用第 5 章预留槽；稳定前缀不变，缓存命中不受影响 |
| 索引上限 | 每份 MEMORY.md 200 行 / 25KB，超限截断 + 警告 | 与参考一致；警告让模型知道索引残缺、避免重复建笔记 |
| 自然结束回调 | Agent 新增可空 `onNaturalStop`，构造后 set | 不侵入协议，Agent 自己知道「无工具调用=自然结束」 |
| 笔记去重 | LLM 见 manifest 后自行判断，同名文件覆盖=更新 | spec「去重交给 LLM」；写层用文件名幂等 |
| 笔记提取客户端 | 无工具独立 `LlmClient`（同 Summarizer） | 复用流式协议，工具列表空 + 提示词双重禁止 |
