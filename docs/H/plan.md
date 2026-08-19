# Skill 系统 Plan

## 架构概览

新增 `com.novacode.skill` 包，核心是一个 `SkillManager` 编排器：启动时扫描三级目录、校验白名单、构建「技能菜单」；运行时处理激活 / 去激活、工具白名单收窄、独立模式子对话、斜杠命令注册与热更新。系统其余部分通过三处小改接入：

1. `ToolRegistry` 增加「按允许名单过滤」的 schema 生成方法，供白名单收窄使用。
2. `Agent` 增加可变工具白名单字段，并把 `setTools` 移进循环、每轮按白名单重算工具集。
3. `ChatModel` 把 `systemPrompt` 改为非 final、每轮重建，注入技能菜单 + 激活技能正文；实现 `CommandContext.invokeSkill` 并接线斜杠命令。

数据流（shared 模式）：`/commit` → 命令处理器 → `ctx.invokeSkill("commit", args)` → `SkillManager.activate`（标记激活 + 收窄白名单 + 注册专属工具）→ SOP 替换占位符后作为用户消息注入 → 启动 Agent → Agent 每轮按白名单暴露工具 → 模型执行 SOP。

数据流（independent 模式）：`/test` → `invokeSkill` → `SkillManager.runIndependent`（开独立历史 + 带 N 条近期 + 嵌套 Agent 跑完）→ 摘要回流主历史。

## 核心数据结构

### Skill（`com.novacode.skill.Skill`）

```java
public record Skill(
    String name,            // 唯一名字（小写）
    String description,     // 一句说明
    List<String> tools,     // 工具白名单；空 = 不收窄
    SkillMode mode,         // SHARED / INDEPENDENT
    int history,            // independent 模式带多少条近期历史
    String model,           // 可选指定模型；"" = 无
    String body,            // SOP 正文（含 {args} 占位符）
    List<Tool> dedicatedTools // 目录型 Skill 的专属工具；空 = 无
) {}
```

### SkillMode

```java
public enum SkillMode { SHARED, INDEPENDENT }
```

### 内置加载工具名

`use_skill`（常量 `SkillManager.USE_SKILL`）。作为普通 Tool 注册进 ToolRegistry，`category = READ`（不触发权限确认）。

## 模块设计

### SkillLoader —— 扫描与解析

**职责**：扫描三级目录、解析 frontmatter + 正文、按优先级合并、跳过坏文件。

**存储布局**：
- 内置：`BuiltinSkills.samples()` 返回代码里写死的 3 个样板。
- 用户级：`~/.mewcode/skills/` 下的 `*.md`（单文件）与 `*/SKILL.md`（目录型）。
- 项目级：`.mewcode/skills/` 下同样两类。

**合并顺序**：先内置，再用户覆盖，再项目覆盖（同名后写覆盖先写）。

**解析**：单文件 = 整文件；目录型 = `<dir>/SKILL.md` 为入口，`<dir>/tools/<name>.json`（schema）+ `<dir>/tools/<name>.sh`（脚本）为专属工具。frontmatter 夹在两个 `---` 之间，用 Jackson YAML 解析字段：`name`、`description`（二者必填）、`tools`（列表）、`mode`（shared/independent，默认 shared）、`history`（整数，默认 0）、`model`（字符串，默认空）。正文为第二个 `---` 之后全部内容。

**坏文件跳过**：缺 name 或缺 description、YAML 解析失败 → 跳过该文件，收集警告；`tools` 里出现不存在的工具名 → 抛异常（在 SkillManager 校验阶段，见下）。

### SkillManager —— 编排器

**职责**：持有「可用技能」与「激活技能」两个集合；构建菜单与激活段；激活 / 去激活；白名单收窄；独立模式子对话；斜杠命令注册与热更新；实现 `use_skill` 工具。

```java
public final class SkillManager {
    public static final String USE_SKILL = "use_skill";

    public SkillManager(ToolRegistry registry, Agent agent,
                        ProviderConfig config, Path projectRoot); // 扫描 + 校验白名单（可能抛）
    public void registerCommands(CommandRegistry commands);        // 注册 /skills + 各技能斜杠命令
    public Skill lookup(String name);                              // 查可用技能
    public String activate(String name, String args);              // 激活，返回替换后的 SOP
    public void deactivateAll();                                   // 清空激活 + 复位白名单
    public String buildSkillSection();                             // 菜单 + 激活正文
    public String runIndependent(Skill skill, String args,
                                List<ChatMessage> mainHistory);    // 独立模式，返回摘要
    public Tool useSkillTool();                                    // use_skill 工具
    public String reload();                                        // 热更新：重扫 + 注册新命令，返回结果文本
    public List<String> listSummary();                             // 供 /skills 展示
}
```

**白名单收窄**：`activeWhitelist()` = 所有激活技能 `tools` 的并集；非空时通过 `agent.setToolWhitelist(union ∪ {USE_SKILL})` 收窄，空则复位为「全工具」。

**菜单 / 激活段**：`buildSkillSection()` 拼两段——`可用 Skills`（名字 + 说明 + 提示用 use_skill 加载）恒在；`已激活 Skills`（各激活技能的完整正文，标注最高优先级）仅激活时在。整体作为 `BuildOptions.skillSection`（priority 90）注入，位于稳定前缀与环境块之后、自定义指令（91）与记忆（92）之前。

### ScriptTool —— 目录型专属工具

实现 `Tool` 接口：`name`/`description`/`schema` 来自 `<name>.json`；`category = READ`；`execute(args)` 把参数序列化为 JSON 写进 `<name>.sh` 的标准输入，用 `bash` 执行，收集 stdout 返回。

### use_skill 工具

schema 参数：`name`（必填，Skill 名）、`args`（可选，占位符替换内容）。执行逻辑：查不到 → 错误；shared → 激活 + 返回完整 SOP；independent → 跑独立对话 + 返回摘要。

## 对既有文件的改动

### ToolRegistry
新增 `getAllSchemas(String protocol, Set<String> allowed)` 与 `getReadOnlySchemas(String protocol, Set<String> allowed)`，内部抽公共 `buildSchemas(protocol, readOnly, allowed)`；allowed 为空语义等同原方法。

### Agent
新增 `volatile Set<String> toolWhitelist = Set.of()` 与 `setToolWhitelist(Set<String>)`。`agentLoop` 把开头的 `setTools` 移到循环内、每轮调用 `computeSchemas(planMode)`：plan 模式用只读 schema；否则白名单空 → 全量，非空 → 过滤为白名单 + `use_skill`。

### CommandContext
新增 `void invokeSkill(String name, String args)`。

### ChatModel
- `systemPrompt` 改为非 final；新增 `buildCurrentSystemPrompt()`，每轮 `startAgent()` 前用 `skillManager.buildSkillSection()` 重建。
- 构造顺序调整：先建 toolRegistry、MCP、权限、contextManager、agent，再建 SkillManager（校验白名单、注册 use_skill），再注册命令与技能斜杠命令，最后构建初始 systemPrompt。
- 实现 `invokeSkill`：shared → `activate` 后 `sendPrompt(SOP)` 触发 Agent；independent → `runIndependent` 后把摘要作为 user 消息注入 `history` 并展示。
- `/clear`（`clearMessages`）与 `/new`（`newSession`）里追加 `skillManager.deactivateAll()`。

## 文件组织

```
src/main/java/com/novacode/skill/
├── Skill.java          — Skill record
├── SkillMode.java      — 执行模式枚举
├── SkillLoader.java    — 三级扫描 + frontmatter 解析 + 合并
├── BuiltinSkills.java  — commit / review / test 三个样板
├── ScriptTool.java     — 目录型专属工具（schema + 脚本）
└── SkillManager.java   — 编排器 + use_skill 工具 + 斜杠命令 + 独立模式

src/main/java/com/novacode/tool/ToolRegistry.java      — 修改：白名单过滤 schema
src/main/java/com/novacode/agent/Agent.java            — 修改：白名单 + 每轮重算工具
src/main/java/com/novacode/command/CommandContext.java — 修改：新增 invokeSkill
src/main/java/com/novacode/ui/ChatModel.java           — 修改：接线 SkillManager + 每轮重建 systemPrompt
target/smoke/Smoke11.java                              — 新建：无头冒烟测试
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 注入点 | 复用 `BuildOptions.skillSection`（priority 90） | 第 5 章已预留该槽，位于稳定前缀之后、自定义指令/记忆之前，既「显眼」又不破坏缓存（N2） |
| use_skill 类别 | READ | 不触发权限确认（系统级工具），且各工具读取路径不序列化 |
| 白名单收窄时机 | Agent 每轮重算 `setTools` | 激活发生在循环中，每轮重算才能让收窄在当前轮内生效 |
| 收窄后保留 use_skill | 过滤名单恒并 `use_skill` | 满足「加载工具系统级、不受白名单约束」（F6） |
| 独立模式权限 | 子对话用 BYPASS 模式 + 无确认通道 | 主循环被 use_skill 阻塞，HITL 会死锁；黑名单/沙箱仍在前置层生效 |
| 斜杠命令冲突 | 技能名与内置命令撞名时跳过注册（内置优先），在 /skills 里标注 | 样板 review 与内置 /review 撞名；避免启动期别名冲突致命退出 |
| 热更新 | `/skills` 与激活前按需重扫；新技能命令在重扫时补注册 | 无需文件监听器，改动下次触发即生效 |
| 坏文件 vs 白名单坏引用 | 解析失败跳过（N4）；白名单引用未知工具致命退出（N3） | 前者是用户误写格式、可降级；后者是安全语义错误、须 fail-fast |
