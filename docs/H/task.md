# Skill 系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/skill/SkillMode.java` | 执行模式枚举 |
| 新建 | `src/main/java/com/novacode/skill/Skill.java` | Skill record（元信息 + 正文 + 专属工具） |
| 新建 | `src/main/java/com/novacode/skill/BuiltinSkills.java` | commit / review / test 三个样板 |
| 新建 | `src/main/java/com/novacode/skill/ScriptTool.java` | 目录型专属工具 |
| 新建 | `src/main/java/com/novacode/skill/SkillLoader.java` | 三级扫描 + frontmatter 解析 + 合并 |
| 新建 | `src/main/java/com/novacode/skill/SkillManager.java` | 编排器 + use_skill 工具 + 斜杠命令 + 独立模式 |
| 修改 | `src/main/java/com/novacode/tool/ToolRegistry.java` | 白名单过滤 schema |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | 白名单字段 + 每轮重算工具 |
| 修改 | `src/main/java/com/novacode/command/CommandContext.java` | 新增 invokeSkill |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 接线 SkillManager + 每轮重建 systemPrompt |
| 新建 | `target/smoke/Smoke11.java` | 无头冒烟测试 |

## T1: SkillMode + Skill

**文件：** `skill/SkillMode.java`、`skill/Skill.java`
**依赖：** 无
**步骤：**
1. 定义 `enum SkillMode { SHARED, INDEPENDENT }`。
2. 定义 `record Skill(name, description, tools, mode, history, model, body, dedicatedTools)`。
3. 提供便捷工厂 `of(name, description, tools, mode, history, model, body)`，dedicatedTools 传空表。

**验证：** `mvn -q compile` 通过，无报错。

## T2: BuiltinSkills（三个样板）

**文件：** `skill/BuiltinSkills.java`
**依赖：** T1
**步骤：**
1. 定义 `commit`（shared，白名单 `[Bash, ReadFile, Grep]`，SOP 引导生成 commit message 并提交）。
2. 定义 `review`（shared，白名单 `[ReadFile, Grep, Glob]`，SOP 审查代码并给建议）。
3. 定义 `test`（shared，白名单 `[Bash, ReadFile]`，SOP 运行测试并汇报）。
4. SOP 正文里用 `{args}` 占位符表示用户可传入的范围。
5. 暴露 `static List<Skill> samples()`。

**验证：** 编译通过；临时 main 打印 3 个样板名字（或留到 Smoke11 验证）。

## T3: ScriptTool（目录型专属工具）

**文件：** `skill/ScriptTool.java`
**依赖：** T1
**步骤：**
1. 实现 `Tool`，从 JSON schema 文件读 `name`/`description`/`input_schema`，从脚本文件读执行命令。
2. `category()` 返回 READ；`schema()` 返回 `Map.of("name","description","input_schema")`。
3. `execute(args)` 把 args 序列化为 JSON 写进脚本 stdin，用 `bash` 执行（Windows 用 `bash`，同 BashTool 的跨平台策略），收集 stdout。

**验证：** 编译通过。

## T4: SkillLoader（三级扫描 + 解析）

**文件：** `skill/SkillLoader.java`
**依赖：** T1、T3
**步骤：**
1. 实现 frontmatter 提取：读文件，取首尾两个 `---` 之间的 YAML，之后为正文。
2. 用 Jackson YAML 解析 `name`/`description`/`tools`/`mode`/`history`/`model`。
3. `load(builtin, userDir, projectDir)`：先放内置，再覆盖用户，再覆盖项目（同名覆盖）。
4. 单文件 `*.md`；目录型读 `<dir>/SKILL.md` + `<dir>/tools/*.json` + `<dir>/tools/*.sh`。
5. 解析失败（缺 name/description、YAML 非法）跳过该文件并记录警告。

**验证：** 编译通过；Smoke11 里验证覆盖顺序与坏文件跳过。

## T5: SkillManager（编排器）

**文件：** `skill/SkillManager.java`
**依赖：** T2、T4、T3，及 ToolRegistry/Agent/CommandContext 的改动（T6–T9）
**步骤：**
1. 构造器：`loader.load(...)` 得到可用技能 Map；遍历校验 `tools` 里每个名字都在 `registry.listTools()` 中，否则抛 `IllegalStateException`。
2. 维护 `active` 集合；`activate(name, args)` 标记激活、注册专属工具、更新白名单、返回替换 `{args}` 后的 SOP。
3. `buildSkillSection()` 拼「可用 Skills 菜单 + 已激活正文」。
4. `useSkillTool()` 返回 use_skill 工具（READ，参数 name/args；shared 激活返回 SOP，independent 跑子对话返回摘要）。
5. `runIndependent(...)`：建独立历史（带 N 条近期 + SOP 用户消息），用嵌套 Agent + BYPASS 权限跑完，取末条 assistant 文本作摘要返回。
6. `registerCommands(registry)`：注册 `/skills`；对每个技能注册斜杠命令（撞名跳过）；`reload()` 重扫并补注册新名字。
7. `deactivateAll()` 清空激活并复位白名单。

**验证：** 编译通过；Smoke11 覆盖激活、白名单、菜单、独立模式、命令注册。

## T6: ToolRegistry 白名单过滤

**文件：** `tool/ToolRegistry.java`
**依赖：** 无
**步骤：**
1. 抽公共 `buildSchemas(protocol, readOnly, allowed)`；`allowed == null` 表示不过滤。
2. 新增 `getAllSchemas(protocol, Set<String> allowed)` 与 `getReadOnlySchemas(protocol, Set<String> allowed)`。
3. 原无参方法委托到 `allowed == null`。

**验证：** 编译通过；原行为不变。

## T7: Agent 白名单 + 每轮重算

**文件：** `agent/Agent.java`
**依赖：** T6
**步骤：**
1. 新增 `volatile Set<String> toolWhitelist = Set.of()` 与 setter。
2. 删掉循环外的 `setTools`，在循环内每轮 `client.setTools(computeSchemas(planMode))`。
3. `computeSchemas`：plan → 只读（可再叠加白名单）；白名单空 → 全量；否则过滤为白名单 ∪ {use_skill}。

**验证：** 编译通过；Smoke11 验证收窄后仅白名单工具 + use_skill。

## T8: CommandContext 新增 invokeSkill

**文件：** `command/CommandContext.java`
**依赖：** 无
**步骤：**
1. 接口新增 `void invokeSkill(String name, String args);`。

**验证：** 编译（会连带要求 ChatModel 实现，配合 T9）。

## T9: ChatModel 接线

**文件：** `ui/ChatModel.java`
**依赖：** T5–T8
**步骤：**
1. `systemPrompt` 改非 final；保存 `env`/`instructions` 为字段；新增 `buildCurrentSystemPrompt()`。
2. 构造顺序调整：agent 之后建 `skillManager`，注册 use_skill 工具、技能斜杠命令，最后构建初始 systemPrompt。
3. `startAgent()` 开头调用 `buildCurrentSystemPrompt()`。
4. 实现 `invokeSkill`：shared → activate + sendPrompt；independent → runIndependent + 摘要进历史。
5. `clearMessages()`/`newSession()` 里调 `skillManager.deactivateAll()`。

**验证：** 编译通过；Smoke11 用 FakeCtx 走一遍，真机留用户测。

## T10: Smoke11 无头冒烟测试

**文件：** `target/smoke/Smoke11.java`
**依赖：** T1–T9
**步骤：**
1. 用临时目录构造项目/用户技能目录，写合法/非法/覆盖/目录型/白名单坏引用等文件。
2. 断言：覆盖顺序、坏文件跳过、白名单校验抛异常、activate 返回替换后 SOP、菜单只含名字+说明、buildSkillSection 含激活正文、独立模式返回摘要、命令注册与撞名跳过、deactivateAll 清空。
3. 用 `$JAVA_HOME/bin/java` 运行，全部 PASS 才结束。

**验证：** 输出 `PASS=... FAIL=0`。

## 执行顺序

```
T1 → T2 → T3 → T4 ──┐
                     ├→ T5 → T9 → T10
T6 → T7 → T8 ────────┘
```
T6/T7/T8 可与 T2–T4 并行；T5 依赖所有前置；T9 依赖 T5；T10 收尾。
