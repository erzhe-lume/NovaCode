# 上下文管理 Plan（第 8 章）

## 架构概览

新增 `com.novacode.context` 包，包含三个组件，负责从工具结果存盘到对话摘要的全部逻辑。Agent 循环在每次发起 LLM 请求前调用它（F10），并在请求返回后回报 usage 以锚定估算（F9）。

```
com.novacode.context
├── TokenEstimator.java   近似 Token 估算（锚定 + 字符增量）
├── Summarizer.java       结构化摘要的 LLM 调用与结果提取
└── ContextManager.java   编排：存盘(预防层) + 摘要(兜底层) + 熔断 + 手动触发
```

- **TokenEstimator**：维护「上次 usage 输入 token + 当时的消息字符数」锚点，当前总量 = 锚点 + 增量字符数 / 每 token 字符数；提供单条消息的估算。
- **Summarizer**：持有一个独立、无工具暴露的 LLM 客户端，按固定摘要系统提示词发起一次流式调用，从返回文本里提取 `<summary>` 段、丢弃 `<draft>` 段。
- **ContextManager**：编排两层逻辑；持有存盘目录、熔断计数、上下文窗口等状态，是 Agent 与 ChatModel 的唯一入口。

## 核心数据结构

### TokenEstimator
- `void anchor(long inputTokens, long messageChars)` — 记录一次真实 usage 与当时的消息字符数。
- `long estimateTotal(long currentMessageChars)` — 当前估算总量。
- `static int estimateTokens(String text)` — 单条文本估算（至少 1）。
- `static long totalChars(List<ChatMessage> history)` — 历史消息字符数合计。

常量：`CHARS_PER_TOKEN = 4`（近似，每 token 约 4 字符；锚定机制吸收其误差）。

### Summarizer
- `String summarize(List<ChatMessage> messages)` — 对消息列表生成结构化摘要，返回正式摘要文本（草稿已丢弃）。抛异常表示摘要失败。
- 内部持有 `LlmClient`（`LlmClient.create(config, null)`，未 `setTools` → 无工具）。

### ContextManager
- `ContextManager(ProviderConfig config)` — 读取上下文窗口、初始化存盘目录与 Summarizer。
- `String prepareBeforeRequest(List<ChatMessage> history)` — 请求前钩子：先存盘（F2/F3）再判断摘要（F4），返回本次动作的人类可读状态（无动作则空串）。可抛 `InterruptedException`。
- `void onUsage(int inputTokens, List<ChatMessage> history)` — 请求后钩子，锚定估算（F9）。
- `String compressNow(List<ChatMessage> history)` — 手动触发（F7），返回状态。

常量（默认值）：
| 常量 | 值 | 含义 |
|------|-----|------|
| `SINGLE_SPILL_TOKENS` | 2000 | 单条工具结果存盘阈值 |
| `BATCH_SPILL_TOKENS` | 4000 | 单条消息工具结果合计存盘阈值 |
| `AUTO_MARGIN` | 13000 | 自动触发安全余量 |
| `MANUAL_MARGIN` | 3000 | 手动触发安全余量 |
| `KEEP_RECENT_TOKENS` | 10000 | 兜底压缩保留的近期 Token |
| `KEEP_MIN_MESSAGES` | 5 | 兜底压缩至少保留的消息数 |
| `MAX_SUMMARY_FAILURES` | 3 | 熔断阈值 |

## 模块设计

### TokenEstimator（近似估算）
**职责：** 提供总量与单条估算，供阈值判断与边界选取使用。
**对外接口：** 见上。
**依赖：** 仅依赖 `ChatMessage`（只读 content）。

### Summarizer（摘要生成）
**职责：** 一次无工具 LLM 调用生成结构化摘要，并按 `<draft>`/`<summary>` 标签拆分，丢弃草稿。
**对外接口：** `summarize(List<ChatMessage>) -> String`。
**依赖：** `LlmClient`、`ProviderConfig`。

摘要系统提示词固定包含：
1. 禁止调用任何工具、只输出文本；
2. 先 `<draft>...</draft>` 分析、再 `<summary>...</summary>` 正式输出，草稿会被丢弃；
3. `<summary>` 内固定五个部分：`## 任务目标`、`## 已完成的工作`、`## 关键决策与结论`、`## 当前状态`、`## 待办事项`；
4. 已存盘工具结果只记位置与要点，不臆测完整内容。

### ContextManager（编排）
**职责：** 两层压缩的调度与状态管理。
**对外接口：** 见上。
**依赖：** `TokenEstimator`、`Summarizer`、`ProviderConfig`、文件系统。

**存盘（F2/F3）流程：** 扫描历史中的 TOOL 消息，按「连续 TOOL 消息段」识别单条消息的批量；先对单条超阈值的存盘（F2），再对段内合计超阈值的按体积降序存盘（F3）。存盘写入 `.novacode/spill/tool_<n>.txt`，历史中替换为「预览 + 相对路径」。已存盘的消息变短，天然幂等，不会重复存盘。

**摘要（F4/F6）流程：**
1. 从尾部按 Token 往回累加，找到保留边界（约 1 万 Token 或至少 5 条）；
2. 边界对齐到安全切点——保留段不得以 TOOL 消息开头（否则其 tool_calls 被摘要掉而成孤儿）；
3. 对 `[0, b)` 调用 Summarizer 生成摘要；
4. 重建历史为 `[摘要消息] + [边界消息] + [保留段]`（边界提示紧邻摘要，保留段保持尾部原文）。

**熔断（F8）：** 摘要调用失败计数；连续 3 次置熔断标志，自动摘要跳过；成功复位。

## 模块交互

```
Agent.agentLoop 每次迭代顶部：
    ContextManager.prepareBeforeRequest(history)
        ├─ spillOversized(history)          // F2/F3 预防层
        └─ 若 estimateTotal + 13000 >= window
             └─ compress(history)           // F4/F6 兜底层 → Summarizer.summarize
    client.stream(history, systemPrompt)    // 正常请求
    ContextManager.onUsage(usageInput, history)  // F9 锚定

ChatModel（用户输入 /compact）：
    ContextManager.compressNow(history)      // F7 手动触发（margin=3000）
```

## 文件组织

```
src/main/java/com/novacode/
├── context/
│   ├── TokenEstimator.java      新
│   ├── Summarizer.java          新
│   └── ContextManager.java      新
├── agent/
│   ├── Agent.java               改：构造器收 ContextManager；循环顶部调用 prepareBeforeRequest；请求后 onUsage
│   └── AgentEvent.java          改：新增 Notice 事件
├── ui/
│   └── ChatModel.java           改：构造 ContextManager 并注入 Agent；/compact 命令；处理 Notice
└── config/
    ├── ProviderConfig.java      改：新增 contextWindow 字段（默认 64000）
    └── ConfigLoader.java        不变（SNAKE_CASE 已把 context_window 映射到 contextWindow）
config.yaml                      改：可选 context_window 字段
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 摘要调用用什么客户端 | 新建独立 `LlmClient`（不 setTools） | 复用现有流式协议；工具列表为空 + 提示词双重禁止，模型无法调工具 |
| 摘要如何「草稿用完即丢」 | 提示词要求 `<draft>`/`<summary>` 标签，代码提取 `<summary>` 段 | 单次调用内实现两段式，无需第二次请求；提取失败降级为整段输出 |
| 压缩切点如何保证协议合法 | 保留段不得以 TOOL 开头，边界对齐到非 TOOL 消息 | OpenAI/Anthropic 都要求 tool 结果紧跟其 assistant tool_calls，切到 TOOL 会孤儿 |
| 存盘发生在何时 | 每次请求前（prepareBeforeRequest 内先于摘要） | 保证摘要调用看到的已是存盘后的短内容，不会二次放大摘要输入 |
| 摘要消息用什么角色 | USER 角色 + `<context-summary>` 包裹 | 与既有 plan reminder 模式一致；system prompt 保持字节稳定以命中缓存 |
| 手动触发是否阻塞 UI | 同步执行 | v1 简单正确；压缩是用户主动操作，短暂阻塞可接受 |
| Token 估算单位 | 字符 / 4 | 锚定机制只对增量估算，误差被限制在增量规模 |
| 上下文窗口来源 | `ProviderConfig.contextWindow` 默认 64000 | 不同模型窗口不同，需可配；默认保守 |
