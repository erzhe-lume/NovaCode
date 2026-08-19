# 命令注册与分发机制 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] 命令包五个类型（CommandType / CommandSpec / CommandHandler / CommandContext / TokenStats）可被引用（验证：编译通过）
- [ ] 解析器正确切分命令名与参数、大小写归一（验证：无头 smoke 打印 parse 结果）
- [ ] 注册中心冲突检测在重复键上抛错（验证：无头 smoke 注册重名命令触发异常）
- [ ] 内置 13 条命令全部注册、无别名冲突（验证：启动不报冲突退出）

## 集成
- [ ] 回车入口分流器接入，斜杠命令不再经 Agent（验证：运行 `/status` 无 streaming / 无 Token 变化）
- [ ] 界面控制接口全部方法被至少一个命令真实调用（验证：编译 + `/help` `/plan` `/do` `/review` `/status` `/exit` 手测）
- [ ] 权限模式状态栏标记与 `setMode` 联动（验证：`/plan` 后状态栏 `plan`，`/do` 后 `default`）

## 编译与测试
- [ ] 项目编译无错误（验证：`mvn -q compile`）
- [ ] 打包产出可运行 jar（验证：`mvn -q package`）
- [ ] 无头 smoke 全绿（验证：临时 main 跑解析 / 注册 / 补全 / 冲突，17 个断言级别）

## 端到端场景
- [ ] 场景 1（帮助与容错）：启动后输入 `/HELP` 列出全部命令；输入 `/foo` 提示用 `/help` 查看（AC2/AC3/AC8）
- [ ] 场景 2（补全）：输入 `/pl` 按 Tab 补全为 `/plan`；输入 `/s` 按 Tab 出现 `/session` `/status` 候选（AC6）
- [ ] 场景 3（模式切换）：`/plan` 后状态栏显示 `plan`，发消息只读；`/do` 后回 `default` 并注入执行提示（AC5/AC9）
- [ ] 场景 4（本地命令零成本）：`/status` `/help` `/clear` 执行全程无 LLM 请求、无 Token 增长（AC4）
- [ ] 场景 5（普通消息不受影响）：输入非斜杠文本，正常流式回复（AC7）
- [ ] 场景 6（隐藏命令）：`/version` 可用但不出现在 `/help` 与 Tab 候选（AC10）
