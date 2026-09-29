# Skill 系统 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] 三个样板 Skill（commit / review / test）可被加载并列出（验证：`/skills` 或 Smoke11 输出三个名字）
- [ ] 单个 Skill 文件（frontmatter + 正文）能被正确解析出名字、说明、白名单、模式、历史（验证：Smoke11 断言字段值）
- [ ] 目录型 Skill 的入口 Markdown + 专属工具脚本能注册并调用（验证：Smoke11 调用 ScriptTool.execute 返回脚本输出）

## 集成
- [ ] 启动时系统提示词 Skill 段只含名字 + 说明，不含完整 SOP（验证：Smoke11 检查 buildSkillSection 未含 SOP 正文标记）
- [ ] 激活后系统提示词钉有完整 SOP，且位于自定义指令 / 记忆之前（验证：Smoke11 断言 section 顺序 / priority）
- [ ] 白名单收窄后可用工具 = 白名单 + use_skill（验证：Smoke11 检查 Agent 计算出的 schema 名单）
- [ ] shared 模式经斜杠命令执行后 SOP 进入主历史（验证：Smoke11 FakeCtx 收到 sendPrompt 内容含 SOP）
- [ ] independent 模式执行后主历史只多摘要（验证：Smoke11 断言返回值为摘要文本）
- [ ] 清空对话清空激活 Skill（验证：Smoke11 调用 deactivateAll 后 buildSkillSection 无激活段）

## 编译与测试
- [ ] `mvn -q compile` 无错误
- [ ] `Smoke11` 全部 PASS（FAIL=0）

## 端到端场景
- [ ] 场景 1：启动（无激活）→ 系统提示词只有技能菜单；输入 `/commit fix bug` → SOP 带 `fix bug` 替换进正文、作为用户消息送 Agent；再 `/skills` 看到 commit 已加载
- [ ] 场景 2：改项目目录里的 `commit.md` → `/skills`（热更新）→ 再次 `/commit` 使用新内容
- [ ] 场景 3：在项目目录放一个白名单引用 `NoSuchTool` 的 Skill → 启动报错、非零退出码
- [ ] 场景 4：放一个坏 YAML 的 Skill → 启动不报错、该文件被跳过、其余正常
