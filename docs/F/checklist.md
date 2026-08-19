# 记忆与持久化 Checklist（第 9 章）

> 每项通过运行代码或观察行为验证，聚焦系统行为。

## 实现完整性
- [ ] 三层指令文件加载并拼接（验证：三处各写 AGENTS.md，`loadInstructions` 返回按「项目根→.mewcode/→~/.mewcode/」顺序的拼接文本）
- [ ] `@include` 相对引用展开、循环/超深拦截（验证：A 包含 B、B 包含 A，返回不无限展开）
- [ ] 跳出项目目录的 `@include` 被拦截（验证：`@../outside.md` 目标在项目外，原行保留）
- [ ] 会话 JSONL 追加写 + 恢复（验证：写入 N 条含工具链，`loadSession` 读回 N 条且 role/工具配对正确）
- [ ] 会话 ID `yyyyMMdd-HHmmss-xxxx`（验证：`newId()` 两次不同）
- [ ] 坏行跳过 + 缺口截断（验证：文件里塞一行坏 JSON，加载不崩；末尾留一条无结果 tool_call，恢复截到缺口前）
- [ ] 30 天清理（验证：造一个旧 mtime 的会话文件，`cleanExpired` 后消失）
- [ ] 会话列表扫 JSONL 现算（验证：`list()` 返回 id/首条消息/消息数，无 meta 文件）
- [ ] 笔记四类分目录 + MEMORY.md 索引（验证：`writeMemoryFile` 落对目录，索引追加指针不重复）
- [ ] 索引 200 行 / 25KB 截断（验证：造超长 MEMORY.md，`buildMemorySection` 输出被截断且带 WARNING）

## 集成
- [ ] 指令 + 记忆注入 system prompt 末尾（验证：`buildSystemPrompt` 输出含指令与记忆段，稳定前缀不变）
- [ ] 自然结束触发异步提取（验证：`agentLoop` `calls.isEmpty()` 后调用 `onNaturalStop`）
- [ ] 每轮提交与循环结束都 `sync` 会话（验证：一轮对话后 JSONL 消息数与 history 一致）
- [ ] `/new` 清空历史并开新会话文件（验证：`/new` 后 JSONL 写入新 id 文件）

## 编译与测试
- [ ] `mvn -q compile` 无错误
- [ ] `mvn -q package` 产出 `target/novacode-1.0.0.jar`

## 端到端场景
- [ ] 场景 1（连续使用）：启动 → 问 A（触发工具）→ 自然结束 → 退出；再启动 → 自动恢复上次会话、显示恢复提示，继续问「刚才那个」能接上（验证：两次启动间 history 与 JSONL 一致）
- [ ] 场景 2（越用越懂）：一轮对话留下项目偏好 → 自然结束 → 异步生成笔记到对应目录 → 重启后 system prompt 里出现该笔记索引
- [ ] 场景 3（容错）：手改会话 JSONL 塞坏行 + 末尾孤儿 tool_call → 启动恢复不崩、缺口截断、坏行跳过
