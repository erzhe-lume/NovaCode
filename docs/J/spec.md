# 子 Agent 委派 Spec

## 背景

主 Agent 在跑长任务时，模型上下文会不断膨胀：研究一个子问题、实现一个组件、审查一段代码，都会把中间过程塞进主对话，既污染上下文又让权限/用量互相纠缠。此前 NovaCode 的 Skill（第 11 章）只能做「固定 SOP 的独立执行」，做不到「把一个任意子任务交给一个真正独立的 Agent 去跑」。

本章引入**子 Agent（sub-Agent）委派**：主 Agent 通过一个统一的 `Agent` 工具，把子任务交给独立的子 Agent 执行。每个子 Agent 拥有自己干净的对话上下文、受限的工具集、独立的权限判定和 token 计数；子 Agent 跑到底（模型不再调工具即算完成），结果异步通知回主对话，从根上解决上下文污染。

对照参考实现 `d:/java-agent/ref-java` 的 `com.mewcode.subagent` 包设计，落到 NovaCode 的多协议 + 五层权限 + TEA TUI 架构上。

## 目标

- 主 Agent 能把子任务委派给独立的子 Agent，隔离上下文与状态。
- 一个统一的 `Agent` 工具，用 `subagent_type` 参数分流「定义式」和「Fork 式」两条路径，工具列表始终稳定。
- 角色用 Markdown + YAML frontmatter 定义，多来源加载、同名覆盖。
- 子 Agent 跑到底、非交互执行，结果异步通知主对话。

## 功能需求

- **F1 统一 Agent 工具**：一个名为 `Agent` 的工具，schema 含 `description`（必填）、`prompt`（必填）、`subagent_type`（可选）、`model`（可选）、`run_in_background`（可选）、`timeout_ms`（可选）。执行时按 `subagent_type` 分流：
  - 有 `subagent_type` → **定义式子 Agent**（从空白对话 + 固定角色启动）。
  - 无 `subagent_type` → **Fork 式子 Agent**（继承父对话历史与工具集）。

- **F2 角色定义（Markdown + YAML frontmatter）**：一个子 Agent 类型 = 一个 `.md` 文件。frontmatter 字段：
  - `name`（必填，角色名）、`description`（必填，用途说明）、`tools`（工具白名单，可选）、`disallowedTools`（工具黑名单，可选）、`model`（模型，可选：缺省/`inherit` = 继承父模型，或 `haiku`/`sonnet`/`opus` 别名）、`maxTurns`（最大轮次，可选）、`permissionMode`（权限模式，可选）。
  - 正文（frontmatter 之后的 Markdown）是子 Agent 的系统提示。

- **F3 多来源加载 + 同名覆盖**：加载优先级（前者覆盖后者）**项目级 > 用户级 > 内置**：
  1. 内置 spec（`general-purpose`、`plan`、`explore`）。
  2. 用户级 `~/.novacode/agents/*.md`。
  3. 项目级 `<projectRoot>/.novacode/agents/*.md`。
  同名角色后者覆盖前者；解析失败的文件静默跳过（不中断启动）。

- **F4 定义式子 Agent**：从空白对话 + 固定角色启动。角色正文作为子 Agent 的 system prompt，用户 `prompt` 作为首条消息。子 Agent 拥有**隔离的运行时状态**（消息、权限、文件读缓存、token 计数），但**共享基础设施**（LLM 客户端、Hook 引擎、文件系统）。

- **F5 Fork 式子 Agent**：继承父对话历史与完整工具集，让首次请求命中 prompt cache 降成本。Fork 强制走后台执行；Fork 子 Agent 不得再次 Fork（运行时拦截嵌套 fork）。

- **F6 跑到底 + 非交互**：子 Agent 以「跑到底」模式执行——模型不再请求工具即算完成（NovaCode `Agent` 已有此语义），全程非交互（无人在回路确认）。

- **F7 工具过滤多层防线**（防止子 Agent 无限嵌套 / 越权）：
  1. **全局禁止**：所有子 Agent 一律拿不到 `Agent`、`use_skill`。
  2. **角色限制**：`disallowedTools`（额外黑名单）+ `tools`（白名单交集）。
  3. **后台白名单**：后台执行的子 Agent 额外受后台工具白名单约束。
  定义式子 Agent 使用全新工具实例 + 全新文件读缓存（状态隔离）；Fork 式子 Agent 复用父工具实例（继承状态、保证 schema 字节一致以命中缓存）。

- **F8 后台任务管理器**：追踪状态（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED）、结果、token 用量；结果异步通知回主对话。进入后台的方式：
  1. **显式指定**：`run_in_background=true`。
  2. **Fork 强制**：Fork 一律后台。
  3. **超时自动**：前台子 Agent 超过 `timeout_ms` 自动转入后台（不重启，续跑同一队列）。
  后台任务超时（`timeout_ms` 作为看门狗）自动标记 FAILED；`cancelTask` 提供手动取消（打断运行线程）。

## 非功能需求

- **N1 状态隔离**：子 Agent 的权限模式、消息列表、文件读缓存、token 计数互不影响，也不污染主 Agent。
- **N2 基础设施共享**：复用同一个 LLM 客户端工厂、Hook 引擎、文件系统与工作目录，不重复建连。
- **N3 不阻塞主循环**：后台子 Agent 跑在虚拟线程上，主 Agent 拿到 task id 后继续，不被阻塞。
- **N4 故障容错**：子 Agent 出错 / 超时 / 被取消都返回可读信息，绝不抛出到主循环。

## 不做的事

- Worktree 文件隔离（子 Agent 与主 Agent 共享工作目录）。
- 多 Agent 团队编排（team_name / teammate / SendMessage）。
- 后台任务的跨会话持久化（任务表仅内存，进程退出即清空）。
- 「手动把运行中的前台子 Agent 切到后台」的 TUI 按键钩子（需在 TEA 渲染/按键路径加钩子，留待后续；`cancelTask` 提供程序级手动取消）。
- 插件级角色来源（`插件` 优先级最低，NovaCode 暂无插件系统，仅文档占位）。
- `haiku`/`sonnet`/`opus` 到具体模型 ID 的映射表（透传别名给 provider 配置；单 provider 场景由用户自行配置）。

## 验收标准

- **AC1** 主 Agent 能通过 `Agent` 工具委派定义式子 Agent，同步拿回结果，结果正确回显。
- **AC2** `Agent` 工具 schema 的 `subagent_type` 枚举来自已加载角色；`description`/`prompt` 缺失返回明确错误。
- **AC3** 项目级同名角色覆盖用户级/内置；`~/.novacode/agents` 与 `.novacode/agents` 的 `.md` 被正确解析，坏文件被跳过。
- **AC4** 定义式子 Agent 的权限、文件读缓存、token 计数与主 Agent 隔离；角色正文成为其 system prompt。
- **AC5** Fork 式子 Agent 继承父历史与工具集、强制后台；fork 子 Agent 再 fork 被拒绝。
- **AC6** 子 Agent 模型不再调工具即完成（跑到底），非交互（无人确认）。
- **AC7** 工具过滤：子 Agent 拿不到 `Agent`/`use_skill`；`disallowedTools`/`tools` 生效；后台白名单生效。
- **AC8** 后台任务管理器正确追踪状态/结果/用量，完成/失败后通知可被主对话排空；`cancelTask` 能取消运行中任务。
- **AC9** `timeout_ms` 超时后前台任务自动转后台或后台任务标记 FAILED，不阻塞主循环。
- **AC10** 全部编译通过、Smoke13 全绿、可打包运行。
