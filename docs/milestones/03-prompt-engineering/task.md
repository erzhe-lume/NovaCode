# 第 5 章：系统提示工程化 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `prompt/PromptSections.java` | 7 固定模块 + environmentSection |
| 新建 | `prompt/PromptBuilder.java` | Section/EnvironmentContext/BuildOptions、组装、环境探测 |
| 新建 | `prompt/PlanModePrompt.java` | plan 提醒频率控制 |
| 修改 | `protocol/StreamEvent.java` | Usage 加 cacheRead/cacheWrite |
| 修改 | `tool/impl/*.java` ×6 | description 双重强化 |
| 修改 | `protocol/LlmClient.java` | 删 setSystemSuffix |
| 修改 | `protocol/OpenAiCompatClient.java` | 删 suffix 拼接、加缓存解析 |
| 修改 | `protocol/AnthropicClient.java` | 结构适配 |
| 修改 | `agent/Agent.java` | system-reminder 注入、删 PLAN_MODE_SUFFIX |
| 修改 | `agent/AgentEvent.java` | UsageEvent 加 cache 字段 |
| 修改 | `ui/ChatModel.java` | 接入组装管线、smoke 日志 |
| 修改 | `config/ConfigLoader.java` | 补 serialVersionUID（N7） |

## T1: Usage 事件扩展
**文件：** `protocol/StreamEvent.java`
**依赖：** 无
**步骤：** Usage record 加 `cacheRead`/`cacheWrite` 字段；保留两参数便捷构造器（冷启动 provider）。
**验证：** `mvn compile` 通过。

## T2: PromptSections 文本模块
**文件：** `prompt/PromptSections.java`（新建）
**依赖：** 无
**步骤：** 7 个固定模块 text block，适配 NovaCode 工具集；`environmentSection(env)` 动态构建。
**验证：** `mvn compile` 通过。

## T3: PromptBuilder 组装与环境探测
**文件：** `prompt/PromptBuilder.java`（新建）
**依赖：** T2
**步骤：** `Section`/`EnvironmentContext`/`BuildOptions` records；`detectEnvironment`；`buildStableModules`/`buildSystemPrompt` 双入口；临时 main 验证稳定块字节一致。
**验证：** 临时测试确认稳定块不含 env、完整 prompt 以稳定块开头、无空槽残留。通过后删除临时测试。

## T4: PlanModePrompt 频率控制
**文件：** `prompt/PlanModePrompt.java`（新建）
**依赖：** 无
**步骤：** `REMINDER_INTERVAL=5`；完整/精简/重入/退出模板；`buildReminder` 频率公式（iteration==1 完整、`(iteration-1)/5 % 5 == 0` 完整、否则精简）。
**验证：** 临时 main 打印 iteration 1/2/5/6/26/51 确认频率（1-5 全量、6-25 稀疏、26-30 全量），planExists 分支正确。通过后删除。

## T5: 6 个工具 description 双重强化
**文件：** `tool/impl/{ReadFile,EditFile,WriteFile,Glob,Grep,Bash}Tool.java`
**依赖：** T2
**步骤：** 每个 description 加入"专用工具优先"与"编辑前必须先 ReadFile"规则；Bash 强调仅当无专用工具时使用。
**验证：** `mvn compile` 通过；ToolRegistry schema 含强化文本。

## T6: 协议层适配
**文件：** `protocol/LlmClient.java`、`protocol/OpenAiCompatClient.java`、`protocol/AnthropicClient.java`
**依赖：** T1
**步骤：** 删 `setSystemSuffix`；OpenAiCompat 删 suffix 拼接、usage 解析缓存字段（cached_tokens 优先 → DeepSeek 顶层兜底）；Anthropic 结构适配。
**验证：** `mvn clean compile` 预期 Agent.java:91 报错（T7 修复）。

## T7: Agent 注入改造
**文件：** `agent/Agent.java`、`agent/AgentEvent.java`
**依赖：** T4、T6
**步骤：** 删 `PLAN_MODE_SUFFIX` + `setSystemSuffix`；for 循环顶部 planMode 时注入 system-reminder（`Files.exists` 判 planExists）；StreamResult/UsageEvent 加 cache 字段。
**验证：** `mvn clean compile` 通过。

## T8: ChatModel 接入组装管线
**文件：** `ui/ChatModel.java`
**依赖：** T3、T7
**步骤：** 删 `SYSTEM_PROMPT` 常量；构造时 detectEnvironment + buildSystemPrompt 一次组装；累计 totalCacheRead/Write；LoopComplete 写 `nova_cache.log`。
**验证：** `mvn clean compile` 通过。

## T9: 集成验证
**文件：** 无（临时测试）
**依赖：** T8
**步骤：** `mvn clean package -DskipTests`；临时综合测试验证：ChatModel 构造、非 git 降级、稳定前缀、plan 注入、缓存解析（HTTP stub 两种格式）；启动冒烟。
**验证：** 24 项无头断言全通过；jar 启动渲染正常。清理临时测试。

## 执行顺序
```
T1 → T2 → T3 → T5（可并行）→ T6 → T7 → T8 → T9
        ↘ T4（独立）────────────────→ T7
```
