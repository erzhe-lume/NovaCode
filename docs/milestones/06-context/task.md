# 上下文管理 Tasks（第 8 章）

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/context/TokenEstimator.java` | 近似 Token 估算 |
| 新建 | `src/main/java/com/novacode/context/Summarizer.java` | 结构化摘要生成 + 草稿丢弃 |
| 新建 | `src/main/java/com/novacode/context/ContextManager.java` | 编排存盘/摘要/熔断/手动触发 |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | 注入 ContextManager，循环钩子 |
| 修改 | `src/main/java/com/novacode/agent/AgentEvent.java` | 新增 Notice 事件 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 构造注入 + /compact + Notice 处理 |
| 修改 | `src/main/java/com/novacode/config/ProviderConfig.java` | 新增 contextWindow 字段 |
| 修改 | `config.yaml` | 可选 context_window |

## T1: TokenEstimator

**文件：** `src/main/java/com/novacode/context/TokenEstimator.java`
**依赖：** 无
**步骤：**
1. 定义常量 `CHARS_PER_TOKEN = 4.0`。
2. 字段 `anchorTokens`、`anchorChars`。
3. `anchor(long inputTokens, long messageChars)`：inputTokens > 0 时记录。
4. `estimateTotal(long currentChars)`：`anchorTokens + round((currentChars - anchorChars) / CHARS_PER_TOKEN)`，负数增量按 0。
5. 静态 `estimateTokens(String)`：`max(1, ceil(len/CHARS_PER_TOKEN))`，空串返 0。
6. 静态 `totalChars(List<ChatMessage>)`：各消息 content 长度之和（null 按 0）。

**验证：** `mvn -q compile` 通过；用一次性 main 验证 `anchor(1000, 4000)` 后 `estimateTotal(8000)` ≈ 2000。

## T2: Summarizer

**文件：** `src/main/java/com/novacode/context/Summarizer.java`
**依赖：** T1（无直接依赖，仅同包）
**步骤：**
1. 构造器 `Summarizer(ProviderConfig)`：`this.client = LlmClient.create(config, null)`（不 setTools）。
2. 常量 `SUMMARY_SYSTEM_PROMPT`：禁止工具、`<draft>`/`<summary>` 两段、五固定部分、存盘结果不臆测。
3. `summarize(List<ChatMessage>)`：`client.stream(messages, SUMMARY_SYSTEM_PROMPT)`，轮询队列收集 `TextDelta` 拼全文，遇 `Error`/超时抛异常。
4. `extractSummary(String raw)`：取 `<summary>` 与 `</summary>` 之间的内容；缺失时降级为整段。
5. 轮询超时 ~90s（与 Agent.streamOnce 一致）。

**验证：** `mvn -q compile`；一次性 main 用假文本 `...<draft>x</draft>...<summary>y</summary>...` 验证 `extractSummary` 返回 `y`。

## T3: ContextManager

**文件：** `src/main/java/com/novacode/context/ContextManager.java`
**依赖：** T1、T2
**步骤：**
1. 常量表（见 plan.md）：单条 2000、批量 4000、余量 13000/3000、保留 10000/5 条、熔断 3。
2. 构造器：`contextWindow = config.getContextWindow()`；`spillDir = Path.of(".novacode/spill")`，`Files.createDirectories`；`summarizer = new Summarizer(config)`；`estimator`。
3. `prepareBeforeRequest(history)`：`spillOversized`（记存盘数）→ 若 `shouldCompress(AUTO_MARGIN)` 且未熔断则 `compress` → 拼状态串。
4. `onUsage(int, history)`：`estimator.anchor(int, TokenEstimator.totalChars(history))`。
5. `compressNow(history)`：`spillOversized` + 无条件 `compress`（忽略熔断，成功复位）。
6. `spillOversized(history)`：按连续 TOOL 段扫描；单条超阈值存盘（F2）；段合计超阈值按体积降序存盘（F3）；返回存盘数。
7. `spill(history, idx)`：写 `.novacode/spill/tool_<n>.txt`，历史替换为「预览（前 600 字符）+ 路径」；写失败则跳过不改。
8. `shouldCompress(margin)`：`estimateTotal(totalChars) + margin >= contextWindow`。
9. `compress(history)`：`findBoundary` → 无则返回「无需压缩」；调 `summarizer.summarize(subList(0,b))`；成功复位熔断、重建历史 `[摘要]+[保留]+[边界]`；失败计数、连续 3 次置熔断、返回失败状态。
10. `findBoundary(history)`：尾部按 Token 累加到约 10000 且已保留 ≥5 条即停；边界对齐到非 TOOL。
11. 摘要消息：USER + `<context-summary>` 包裹；边界消息：USER + `<system-reminder>`（提示重读文件、勿臆测）。

**验证：** `mvn -q compile`；一次性 main 构造含超长工具结果的 history，调用 `spillOversized` 断言存盘文件存在、历史变短；构造长 history 调 `findBoundary` 断言边界合法。

## T4: Agent 接入

**文件：** `src/main/java/com/novacode/agent/Agent.java`
**依赖：** T3
**步骤：**
1. 新增字段 `ContextManager contextManager`，构造器加参。
2. 循环顶部（interrupt 检查后、plan reminder 前）调 `prepareBeforeRequest(history)`，非空状态 emit `Notice`。
3. `streamOnce` 返回后、追加新消息前：`if (result.usageInput > 0) contextManager.onUsage(result.usageInput, history)`。

**验证：** `mvn -q compile` 通过。

## T5: AgentEvent + ChatModel 接入

**文件：** `src/main/java/com/novacode/agent/AgentEvent.java`、`src/main/java/com/novacode/ui/ChatModel.java`
**依赖：** T4
**步骤：**
1. `AgentEvent` 新增 `record Notice(String message) implements AgentEvent {}`。
2. `ChatModel`：字段 `ContextManager contextManager`；构造器 `new ContextManager(config)` 并注入 `new Agent(..., contextManager)`。
3. `pollAgent()` 增加 `case AgentEvent.Notice` → `msgs.add(new Msg("tool", message, false))`。
4. `submit()` 增加 `/compact` 命令：清输入、`contextManager.compressNow(history)`、结果以 `Msg("tool", ...)` 反馈。

**验证：** `mvn -q compile` 通过。

## T6: 配置项 context_window

**文件：** `src/main/java/com/novacode/config/ProviderConfig.java`、`config.yaml`
**依赖：** T3
**步骤：**
1. `ProviderConfig` 新增 `private int contextWindow = 64000;` + getter/setter。
2. `config.yaml` 的 DeepSeek provider 下加 `context_window: 64000`（带注释说明）。

**验证：** `mvn -q compile`；一次性 main 读 config.yaml 断言 `contextWindow == 64000`。

## 执行顺序

```
T1 → T3 → T4 → T5
       ↘
T2 → T3
T3 → T6（可并行）
```
