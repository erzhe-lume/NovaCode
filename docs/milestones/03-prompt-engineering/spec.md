# 第 5 章：系统提示工程化 Spec

## 背景
前 4 章完成了终端聊天、工具调用与 ReAct Agent Loop。目前 system prompt 是一个硬编码的三行常量（`ChatModel.SYSTEM_PROMPT`），无法随任务动态调整，也导致每次请求整段提示词重复计费。参考 MewCode 第 5 章，将系统提示工程化：模块化组装、稳定/易变通道分离以命中缓存、关键规则双重强化、system-reminder 注入、plan mode 提醒频率控制。

## 目标
- 模块化、优先级驱动的 system prompt 组装管线
- 稳定内容（角色/规则/工具说明）进入可缓存前缀；易变内容（环境信息）进入完整 prompt 的尾部
- 在工具 description 与 system prompt 中双重强化关键规则
- `<system-reminder>` 注入机制替代旧的 `setSystemSuffix`
- plan mode 提醒带频率控制地按轮次注入
- 解析 DeepSeek 缓存命中字段，用 smoke 日志验证

## 功能需求
- F1: 提供按优先级排序的模块化 system prompt 组装。7 个固定模块：Identity(0)、System(10)、DoingTasks(20)、ExecutingActions(30)、UsingTools(40)、ToneStyle(50)、TextOutput(60)。可选模块（Skills/CustomInstructions/Memory）仅在非空时加入。
- F2: 稳定/易变通道分离。稳定前缀（7 固定模块）与完整 prompt（稳定前缀 + 环境块 + 可选模块）由两个独立入口产出，保证环境变化时稳定前缀逐字节一致。
- F3: 环境信息（工作目录/平台/Shell/git 状态/版本/模型/日期）放在完整 prompt 末尾（用户决策，非 messages 通道）。
- F4: 关键规则双重强化：专用工具优先于 Bash、编辑前必须先 ReadFile，同时写入 UsingTools 模块与各工具 description。
- F5: 完全移除 `setSystemSuffix` 机制，替换为 system-reminder 注入。缓存统计不显示在状态栏，改为每轮结束后写入 `nova_cache.log`（smoke 验证用）。
- F6: `<system-reminder>` XML 标签包裹的 user-role 消息，追加到每轮 history，不写入持久历史、不污染 system 缓存。
- F7: plan mode 提醒按迭代频率注入：`REMINDER_INTERVAL=5`，iteration==1 完整，`(iteration-1)/5 % 5 == 0` 完整，否则精简。含完整/精简/重入/退出四类模板。
- F8: DeepSeek 路径解析缓存命中字段：`prompt_tokens_details.cached_tokens` 优先，DeepSeek 顶层 `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` 兜底。Anthropic 只做结构适配（system 字段接收组装后的 prompt），不实现缓存解析。

## 非功能需求
- N1: 稳定前缀在环境信息变化时保持逐字节一致（缓存命中前提）。
- N2: 组装结果无空槽残留（null/空模块被跳过）。
- N3: 环境探测失败静默降级（非 git 目录不报错）。
- N4: 全部改动保持 Java 21 + text block 风格，与现有代码一致。
- N5: 不引入新依赖。
- N6: plan mode 频率控制的验证通过临时测试确认。
- N7: `mvn compile` 无明显警告（不引入 spotless）。
- N8: 缓存 smoke 日志只写入文件，不影响状态栏布局。

## 不做的事
- 项目指令文件（CLAUDE.md 类）加载
- 自动记忆/长期记忆
- 真实 MCP 集成
- 自动化评测（evals）
- Anthropic 路径的缓存断点与 usage 解析（仅结构适配）
- 状态栏显示缓存 token

## 验收标准
- AC1: `buildStableModules()` 不含任何环境信息，跨环境调用字节一致
- AC2: `buildSystemPrompt(env, opts)` 以稳定前缀开头，含环境块，无空槽残留
- AC3: 环境块包含工作目录/平台/Shell/git 状态/版本/模型/日期，git 分支存在时输出
- AC4: 6 个工具 description 与 UsingTools 模块均含"专用工具优先、编辑前先读"规则
- AC5: `setSystemSuffix` 及其拼接逻辑完全移除，编译通过
- AC6: plan mode 时每轮 LLM 调用前的 history 中出现 `<system-reminder>` 消息
- AC7: plan mode 频率符合 `REMINDER_INTERVAL=5` 公式（临时 main 打印验证）
- AC8: 非 plan mode 时无 system-reminder 注入
- AC9: DeepSeek 格式 usage 正确解析 cacheRead/cacheWrite（含两种字段形态）
- AC10: 冷启动 provider（无缓存字段）解析为 cacheRead=0/cacheWrite=0
- AC11: `mvn clean package -DskipTests` 成功
- AC12: 启动正常，无崩溃；chat 一轮结束写出 `nova_cache.log`
- AC13: 非 git 目录下环境探测不报错，isGitRepo=false
- AC14: 环境信息位于完整 prompt 末尾（在稳定模块之后）
