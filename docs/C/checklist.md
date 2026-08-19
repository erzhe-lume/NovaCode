# 第 6 章：五层权限系统 Checklist

> 每一项通过运行代码或观察行为来验证。验证方式见括号。临时无头验证跑在 `.tmp_test/PermissionVerify.java`（已跑完并删除，58 项断言全部 PASS）；人工项由用户在实际终端操作验证。

## 实现完整性
- [x] 判定核心 PermissionEngine 已实现并覆盖五层流水线（验证：`mvn compile` 通过 + 无头断言 58 项全 PASS）
- [x] Blacklist / PathSandbox / RuleEngine / PermissionMode 各模块可独立调用（验证：无头断言各层单独生效）

## 黑名单与沙箱
- [x] 黑名单硬拦截：`Bash("rm -rf /")`（及 `rm -rf ~`、`rm -rf /home`）在 **bypassPermissions** 模式下仍判 Deny、不执行，拒绝原因回灌含来源（验证：无头断言 T2「BYPASS 下黑名单仍 DENY」）
- [x] 黑名单不误伤：`rm -rf ./target`、`ls -la` 不命中（验证：无头断言 T2「良性命令不误伤」）
- [x] 沙箱围栏：文件工具读写项目根外（`../outside` 等）判 Deny（验证：无头断言 T3「根外路径拒绝」+「沙箱在规则前短路」）
- [ ] 沙箱防符号链接逃逸：项目内软链接指向项目外的目标判 Deny（验证：无头断言已实现但**本机 SKIP**——Windows 需管理员/开发者模式才可建符号链接；逻辑经「最近祖先解析 + 前缀比对」实现，建议在可建链接的环境复测）
- [x] 沙箱放行新建文件：含未创建多级中间目录的目标判 Allow（验证：无头断言 T3「不存在的深层路径」）

## 规则引擎
- [x] 精确匹配：`Write(target.txt)` 放行 `target.txt`、不放行 `other.txt`（验证：无头断言 T4）
- [x] glob 匹配：`Read(**/*.md)` 放行 `docs/readme.md`、`readme.md`、不放行 `docs/a.txt`（验证：无头断言 T4；命令串 glob 语义由 globToRegex 的 command/path 分支覆盖）
- [x] 不带模式段：`Bash` 匹配该工具全部调用（验证：无头断言 T4）
- [x] deny 规则命中即拒（验证：无头断言 T4「同 tier deny 优先」+ T6「显式 deny 盖过 BYPASS」）
- [x] 友好名路由：Bash/Read/Write/Edit/Glob/Grep 映射到 6 个内置工具（验证：extract switch 编译期穷尽 + 无头断言经 engine.decide 走 Bash/WriteFile/Glob 全链，Read/Grep/Edit 由同一 switch 覆盖）

## 配置加载
- [x] 三级优先级：本地盖过项目、项目盖过用户（验证：无头断言 T4「最近 tier 优先」两个方向 + T5 本地层生效）
- [x] 同层 deny 优先于 allow（验证：无头断言 T4）
- [x] 配置降级：缺失→空规则；格式非法→跳过该层、不抛异常；空配置→DEFAULT + 空引擎（验证：无头断言 T5）
- [x] defaultMode 优先级 本地>项目>用户，皆无→default（验证：无头断言 T5 本地 acceptEdits 生效 + 空→DEFAULT；firstMode 按 本地→项目→用户 顺序扫描）

## 模式矩阵与运行时切换
- [x] 模式矩阵逐档逐类：default(只读Allow/写+命令Ask)、acceptEdits(写Allow/命令Ask)、bypassPermissions(全Allow，黑名单/沙箱除外)、plan(只读Allow/写+命令Ask)（验证：无头断言 T1）
- [ ] Shift+Tab 循环 default→acceptEdits→plan→bypassPermissions→default（验证：实际终端操作）
- [ ] `/plan` 进 plan、`/do` 固定回 default 并触发执行（验证：实际终端操作）
- [x] 状态栏左侧常驻显示当前权限模式、不再显示 provider 名（验证：启动冒烟输出 `[default] … model`，provider 名已被模式占据）
- [ ] 模式跨轮保持：切换后下一轮对话不被重置（验证：实际终端操作）

## 人在回路
- [ ] Ask 时弹出多行确认块：工具名 + 关键参数 + 触发原因 + 三选项菜单（验证：实际终端操作）
- [ ] ↑↓ 移动高亮 + Enter 确认；数字键 1/2/3 直选；默认高亮「允许本次」（验证：实际终端操作）
- [ ] 允许本次→执行且不留规则（验证：实际终端操作 + 检查本地配置无新增）
- [x] 永久→精确 allow 规则写入 `.novacode/permissions.local.yaml`、落盘后同一引擎不再询问（验证：无头断言 T7「永久规则写入本地文件」+「再次调用命中落盘规则不再询问」）
- [ ] 拒绝本次→Deny 回灌、Agent Loop 继续、模型可改路径（验证：实际终端操作 + 无头断言 T8 已证回灌不中断）
- [x] 等待中 Esc/Ctrl+C 干净取消本轮：不退出程序、无阻塞线程泄漏、取消后再发消息可继续（验证：无头断言 T8「取消后剩余调用回填」+「取消干净结束」；虚拟线程经 drain 队列补 future 防泄漏）

## 集成与保序
- [x] 单批多调用：被拒的与放行的结果按调用序与各自调用 ID 配对回灌、互不串位；Loop 继续（验证：无头断言 T8「黑名单/规则拒绝回填」+「循环继续到自然完成」）
- [x] 只读批量仍并发执行、不触发 Ask（权限检查不把只读串行化）（验证：无头断言 T8「读批并发执行 3×400ms 实耗 418ms」）
- [x] 未知工具/参数无法解析时按最严处理（Ask 或 Deny），不静默放行（验证：无头断言 T6「未知工具拒绝」+ T7「无确认通道降级 Deny」）
- [x] 跨协议一致：权限判定在 agent 编排层，provider 适配层未改动（验证：本次 diff 仅涉及 permission 包 + agent/AgentEvent + ui/ChatModel）

## 编译与测试
- [x] `mvn compile` 无警告（`-Xlint:all`）、`mvn clean package` 成功（验证：BUILD SUCCESS 零警告、shaded jar 生成）
- [x] 启动冒烟：`java -jar target/novacode-1.0.0.jar` 正常进入 TUI（验证：headless 运行 4s 渲染出完整界面 + `[default]` 状态栏，无 crash）
- [x] 本地配置层已被 gitignore、CLI 输出与配置回显不泄漏 api_key（验证：`.gitignore` 含 `permissions.local.yaml`；api_key 回显行为为既有 ch04/05 已验证项）

## 不破坏 ch04/ch05
- [ ] 多轮连环、用户取消、流出错恢复、历史一致、缓存命中、plan 按轮次注入仍成立（验证：实际终端操作；Agent 无头全链已验证多调用批处理与取消路径，与 ch04/05 语义一致）
- [x] 临时无头集成验证全部通过（验证：`.tmp_test/PermissionVerify.java` 58 项断言全部 PASS，临时目录已删除）

## 端到端场景
- [ ] 场景 1（黑名单硬拦）：default 模式让模型执行 `rm -rf /` → 工具未执行、模型收到拒绝原因并自行调整（验证：实际终端操作）
- [ ] 场景 2（确认-永久-重启生效）：default 模式让模型运行某命令 → 弹确认块 → 选「永久」→ 执行成功；退出重启 NovaCode，同命令不再询问直接执行（验证：实际终端操作）
- [ ] 场景 3（沙箱 + 规则放行）：配置 `Write(src/**)` 放行、让模型改项目内源码 → 直接放行；让它改 `/etc/passwd` → 拒绝回灌（验证：实际终端操作）
- [ ] 场景 4（Shift+Tab + 状态栏）：连续 Shift+Tab 观察状态栏模式变化；模式在下一轮对话中保持（验证：实际终端操作）
- [ ] 场景 5（取消安全）：Ask 确认块弹出时按 Ctrl+C → 本轮干净取消、无残留阻塞、可继续对话（验证：实际终端操作）
