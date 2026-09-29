# Hook 系统 Checklist（第 12 章）

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] 9 事件枚举 + 4 动作类型已实现且可被引用（验证：`mvn compile` 通过，Smoke12 引用 `HookEvent.PRE_TOOL_USE` 等）
- [ ] 条件求值支持 `==`/`!=`/`=~`/`=*` 四种操作符（验证：Smoke12 逐操作符断言真/假）
- [ ] `Rule.globToRegex` 已复用且 `file_path` 走路径语义（验证：`file_path =* "src/*.go"` 命中 `src/a.go`、不命中 `src/x.py`）
- [ ] 四种动作 COMMAND/PROMPT/HTTP/AGENT 均已实现（验证：Smoke12 各触发一次并断言结果）

## 集成
- [ ] Agent 在 turn_start/pre_send/post_receive/turn_end/pre_tool_use/post_tool_use 正确触发 hook（验证：Smoke12 FakeClient 快照历史含 turn_start 注入、pre_tool_use 拦截）
- [ ] ChatModel 在 session_start/session_end/shutdown 触发 hook（验证：终端启动/`/new`/退出观察注入与日志）
- [ ] 所有公开接口至少被一个真实调用方使用（验证：编译 + Smoke12 全通过）

## 编译与测试
- [ ] `mvn compile` 无警告、`mvn clean package` 成功
- [ ] Smoke12 输出 `PASS=N FAIL=0`

## 端到端场景
- [ ] 场景 1：配置 `pre_tool_use` + `reject` 的 prompt hook（条件 `args.command =~ /rm -rf/`），让模型跑 `rm -rf` → 工具不执行、拒绝原因作为工具结果回灌、模型改路径继续（观察：终端工具结果显示 `Hook 拦截`，Loop 不中断）
- [ ] 场景 2：配置 `post_tool_use` 的 command hook（`echo ${tool} >> log`）→ 每次工具执行后命令自动跑、日志追加（观察：日志文件逐行新增）
- [ ] 场景 3：配置 `session_start` 的 prompt hook → 启动后每轮 system prompt 含「会话注入」段（观察：行为上模型知晓注入内容）
- [ ] 场景 4：非法 hook 配置（混用 `&&`/`||`、pre_tool_use 声明 async）→ 启动打印集中校验错误、程序照常运行（观察：stderr 有聚合错误、会话可继续对话）
- [ ] 场景 5：`once: true` 的 hook 同会话只触发一次（观察：第二次同类事件不再执行）
