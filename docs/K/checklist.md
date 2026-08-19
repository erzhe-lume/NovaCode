# 第 14 章 Git Worktree 隔离 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] `SlugValidator` 拒绝 `..`/`.`/绝对路径/空段/超长/非法字符，放行合法名（验证：Smoke14 校验用例）
- [ ] `WorktreeManager` 能在临时仓库建/删/列 worktree（验证：Smoke14 真实 git 断言）
- [ ] `AgentWorktree.create` 支持 fast-resume（二次 create 不跑 git、head 一致）（验证：Smoke14）
- [ ] `PostCreationSetup` 复制清单文件 / 软链 / settings / hooksPath（验证：Smoke14 有清单断言）
- [ ] `StaleCleanup` 三层过滤：过期空目录删、有改动保留、未到期保留（验证：Smoke14）
- [ ] 工具 cwd 注入后相对路径解析到指定目录（验证：Smoke14 ReadFile/Glob/Bash）
- [ ] `SubAgentSpec`/`AgentLoader` 解析 `isolation: worktree`（验证：Smoke14 角色解析）
- [ ] `AgentTool` 派发隔离角色时创建/清理 worktree、注入通知（验证：Smoke14 假 client 端到端）

## 集成
- [ ] `ToolFilter` 把 cwd 注入全新工具实例，`SubAgentRunner.buildAgent` 用 cwd 重建沙箱（验证：Smoke14 断言实例 cwd 与沙箱根）
- [ ] `WorktreeService.enter/exit` 贯通 create→setup→notice→改动检测→remove（验证：Smoke14 端到端）
- [ ] 所有公开接口至少被一个真实调用方使用（验证：编译 + Smoke14 全部通过）

## 编译与测试
- [ ] 项目编译无错误（`mvn -q compile`）
- [ ] Smoke14 PASS 全绿、FAIL=0
- [ ] `mvn -q package` 生成 `target/novacode-1.0.0.jar` 与 `-shaded.jar`

## 端到端场景
- [ ] 场景 1：两个 worktree 各自写文件互不干扰（AC1）→ 在 A 写 `x`，B 看不到，反之亦然
- [ ] 场景 2：隔离子 Agent 无改动跑完 → worktree 目录被自动删除（AC6）
- [ ] 场景 3：隔离子 Agent 产生改动 → worktree 目录保留且改动仍在（AC6/AC7）
- [ ] 场景 4：带未提交/未推送改动的目录 `exit` 拒绝删除（AC7）
