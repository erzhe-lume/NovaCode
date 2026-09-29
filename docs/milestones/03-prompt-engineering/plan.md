# 第 5 章：系统提示工程化 Plan

## 架构概览
新增 `prompt` 包承载提示词工程：`PromptSections`（7 固定模块文本）、`PromptBuilder`（优先级组装 + 环境探测 + 双入口）、`PlanModePrompt`（plan 提醒频率控制）。改造协议层（移除 suffix 机制、加缓存解析）、Agent（system-reminder 注入）、ChatModel（接入组装管线、缓存 smoke 日志）。

## 核心数据结构

### `Section(name, priority, content)` — PromptBuilder 内 record
按 `priority` 升序排序后以 `\n\n` 连接；null/空内容被跳过。

### `EnvironmentContext(workDir, os, arch, shell, isGitRepo, gitBranch, appVersion, model, date)`
环境快照。`detectEnvironment(model)` 从系统属性 + git 命令探测，失败静默降级。

### `BuildOptions(skillSection, customInstructions, memorySection)`
可选模块槽位（本章保持 null，仅非空时组装）。

### `StreamEvent.Usage(inputTokens, outputTokens, cacheRead, cacheWrite)`
扩展的 usage 事件；两参数便捷构造器（冷启动 provider）。

## 模块设计

### `prompt/PromptSections.java`
7 个固定模块的 factory method，文本适配 NovaCode 实际工具集（无 Agent 委托/团队工具）。`environmentSection(env)` 动态构建环境文本（priority 70）。

### `prompt/PromptBuilder.java`
- `buildStableModules()`：仅 7 固定模块 → 可缓存前缀
- `buildSystemPrompt(env, options)`：固定模块 + 环境块 + 可选模块
- `detectEnvironment(model)`：环境探测

### `prompt/PlanModePrompt.java`
- `buildReminder(planPath, planExists, iteration)`：频率控制（`REMINDER_INTERVAL=5`）
- `buildReentryReminder` / `buildExitReminder`

### `protocol/` 改造
- `LlmClient`：删除 `setSystemSuffix` default
- `OpenAiCompatClient`：删除 suffix 拼接；usage 解析缓存字段（`prompt_tokens_details.cached_tokens` 优先 → DeepSeek 顶层 hit/miss 兜底）
- `AnthropicClient`：结构适配，`stream(history, prompt)` 已把 prompt 作为 system 字段

### `agent/Agent.java`
删除 `PLAN_MODE_SUFFIX`；每轮 for 循环顶部按 planMode 注入 system-reminder（`Files.exists` 判断 planExists）；`StreamResult` 携带 cacheRead/cacheWrite。

### `ui/ChatModel.java`
删除 `SYSTEM_PROMPT` 常量；构造时 `detectEnvironment` + `buildSystemPrompt` 一次组装复用；累计 cache 字段；LoopComplete 写 `nova_cache.log`。

## 模块交互
```
ChatModel.<init>
  → PromptBuilder.detectEnvironment(model) → env
  → PromptBuilder.buildSystemPrompt(env, opts) → systemPrompt（LlmClient.create + agent.run 复用）
Agent.agentLoop 每轮:
  planMode? → PlanModePrompt.buildReminder(...) → history.add(<system-reminder>...)
  → client.stream(history, systemPrompt)
OpenAiCompatClient.doStream → usage 解析 → StreamEvent.Usage(cacheRead, cacheWrite)
  → Agent.streamOnce → UsageEvent(cache...) → ChatModel 累计 → LoopComplete 写 nova_cache.log
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 环境信息位置 | system prompt 末尾 | 用户决策；稳定前缀保持缓存字节一致 |
| suffix 机制 | 完全移除 → system-reminder | 用户决策；suffix 拼接污染 system 缓存 |
| 缓存解析协议 | 聚焦 DeepSeek；Anthropic 结构适配 | F8 用户决策 |
| plan 文件位置 | `<workdir>/.novacode/plans/plan.md`（确定性路径） | YAGNI，无需 slug 生成；模型用 WriteFile 创建 |
| 缓存统计展示 | `nova_cache.log` 文件，非状态栏 | F5 用户决策 |
| 代码规范 | `mvn compile` 无警告 | N7 用户决策，不引入 spotless |
