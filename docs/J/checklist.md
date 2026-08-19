# 子 Agent 委派 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性

- [ ] `SubAgentSpec` 含三个内置角色（general-purpose / plan / explore），字段齐全（验证：Smoke13 读字段）。
- [ ] `AgentLoader` 三级加载 + 同名覆盖 + 坏文件跳过（验证：Smoke13 Part A 临时目录）。
- [ ] `ToolFilter` 全局禁止 / 角色黑名单 / 角色白名单 / 后台白名单 / 全新实例（验证：Smoke13 Part B）。
- [ ] `SubAgentTaskManager` 状态迁移 + 通知排空 + 取消 + 看门狗超时（验证：Smoke13 Part C）。
- [ ] `AgentTool` schema 枚举、缺参报错、未知类型报错、fork 嵌套拦截（验证：Smoke13 Part D）。

## 集成

- [ ] `AgentTool` 注册进主 `ToolRegistry`，主 Agent 可见（验证：`listTools()` 含 `Agent`）。
- [ ] 子 Agent 复用主 `Agent` 循环跑到底（验证：Smoke13 假 LlmClient 触发 LoopComplete）。
- [ ] `PermissionEngine.forSubAgent` 隔离权限模式且无人在回路（验证：非交互模式下 Ask 降级 Deny，Smoke13 覆盖）。
- [ ] 后台任务完成通知在每轮 LoopComplete 后被排空注入主历史（验证：ChatModel 接线编译 + 手动终端跑一个后台子 Agent 后下一轮能看到完成提示）。

## 编译与测试

- [ ] `mvn -q compile` 无错误（`-Xlint:all` 下 0 警告）。
- [ ] `Smoke13` 全部 PASS、`FAIL=0`。
- [ ] `mvn -q package` 产出 `target/novacode-1.0.0-shaded.jar`。

## 端到端场景

- [ ] 场景 1（定义式同步）：主对话要求 `Agent` 用 `general-purpose` 做一个小任务 → 子 Agent 独立跑完 → 结果内联回显，主上下文无子过程污染（验证：终端手动，看回显）。
- [ ] 场景 2（Fork 后台）：主对话让 `Agent` 不指定类型 fork 一个子任务 → 返回 task id → 子 Agent 后台跑完 → 下一轮主对话看到「后台任务完成」通知（验证：终端手动，观察 task id 与通知）。
- [ ] 场景 3（嵌套拦截）：fork 子 Agent 再尝试 fork → 返回「cannot fork from a forked agent」错误（验证：Smoke13 + 终端）。
- [ ] 场景 4（超时转后台）：`timeout_ms` 很小的定义式前台任务超时 → 返回「已转后台」而非阻塞（验证：Smoke13 看门狗 + 终端）。
