# 命令注册与分发机制 Plan

## 架构概览

新增 `com.novacode.command` 包，负责命令的元数据、解析、注册与分发。`ChatModel`（TEA 架构里的 Model）改为实现命令包定义的界面控制接口，并在回车入口加分流器：输入以斜杠开头走 `CommandRegistry` 分发，否则维持原「普通消息送 Agent」路径。Tab 键在输入框以 `/` 开头时走补全逻辑。

分三层：

1. **类型层**（`CommandType`、`CommandSpec`、`CommandHandler`、`TokenStats`）：纯数据与函数式接口，不含任何渲染或 Agent 依赖。
2. **解析 / 注册层**（`CommandParser`、`CommandRegistry`）：斜杠解析 + 元数据注册 + 冲突检测 + 补全候选。
3. **内置命令装配层**（`BuiltinCommands`）：把十个高频命令 + 既有 `/new`、`/exit`、隐藏的 `/version` 注册进注册中心；处理函数只依赖界面控制接口。

命令实现通过 `CommandContext`（界面控制接口）操作界面，`ChatModel` 提供该接口的具体实现。这样命令不绑定 JLine / TEA 渲染框架。

## 核心数据结构

### CommandType（枚举）

```
LOCAL       // 纯本地：只计算并展示，不改状态、不调 AI
UI_STATE    // 影响界面状态：改消息区 / 模式 / 会话
PROMPT      // 预设提示词：把预设文本送进对话并启动 Agent
```

### CommandSpec（记录）

```
record CommandSpec(
    String name,              // 主命令名（不含斜杠，小写）
    List<String> aliases,     // 别名（小写）
    String description,       // 简短描述（/help 用）
    String usage,             // 用法示例，如 "/plan"
    CommandType type,         // 执行模式
    String paramHint,         // 可选参数提示；无参数为 null
    boolean hidden,           // 是否隐藏（不出现在 /help 与补全）
    CommandHandler handler    // 处理函数
)
```

### CommandHandler（函数式接口）

```
@FunctionalInterface
interface CommandHandler {
    void handle(CommandContext ctx, String args);
}
```

### CommandContext（界面控制接口，spec F4）

```
interface CommandContext {
    void display(String content);          // 显示一条系统/工具消息到消息区（不进对话历史）
    void sendPrompt(String content);       // 把预设提示词作为用户消息注入历史并启动 Agent
    void setMode(PermissionMode mode);     // 切换权限模式（/plan /do）
    TokenStats tokens();                   // 查询 Token 用量与上下文估算
    void refreshStatus();                  // 刷新状态栏（TEA 下自动重绘，接口保留以解耦）
    void quit();                           // 退出程序（/exit；扩展自 spec 的「至少支持」）
}
```

### TokenStats（记录）

```
record TokenStats(
    int inputTokens, int outputTokens,
    int cacheRead, int cacheWrite,
    long contextTokens, int contextWindow
) {}
```

## 模块设计

### CommandParser

**职责**：把一行输入解析成「命令名 + 参数」。只识别斜杠前缀，不处理非命令输入。

**对外接口**：
```
static Optional<Parsed> parse(String input)
record Parsed(String name, String args) {}
```

规则：`input` 不以 `/` 开头 → 返回空；去掉前导 `/`，取第一个空格之前为命令名、之后为参数；命令名转小写；命令名空（如输入只有 `/`）返回空。

### CommandRegistry

**职责**：登记命令元数据、检测冲突、按名查找、提供补全候选与可见命令列表。

**对外接口**：
```
void register(CommandSpec spec)
CommandSpec lookup(String name)       // 小写查找；未命中返回 null
List<CommandSpec> visible()           // 非隐藏命令，按注册顺序
List<String> complete(String prefix)  // 前缀补全候选（小写，排除隐藏）
```

冲突检测：维护一个「小写键 → 命令」映射；注册主名与每个别名时，若键已存在（大小写不敏感）即抛出未检查异常，向上冒泡至入口的全局异常处理，`System.exit(1)`（spec F1/N3）。

### BuiltinCommands

**职责**：装配并注册全部内置命令。构造时接收界面控制接口与命令所需依赖（配置、上下文管理、会话存档、记忆管理、权限配置）。

**对外接口**：
```
static CommandRegistry build(CommandContext ctx, ContextManager cm,
    SessionStore ss, MemoryManager mm, PermissionConfig pc, ProviderConfig cfg)
```

各命令处理逻辑：

| 命令 | 别名 | 类型 | 行为 |
|------|------|------|------|
| `/help` | `?` | LOCAL | 列出全部可见命令的 `usage` + `description` |
| `/status` | — | LOCAL | 显示当前模式、模型、会话 ID、消息数、累计 Token（↑↓ cache）、上下文估算 / 窗口 |
| `/version` | — | LOCAL（隐藏） | 显示版本号（AC10 测试靶点） |
| `/clear` | `cls` | UI_STATE | 清空可见消息区，保留对话历史 |
| `/plan` | — | UI_STATE | 切换权限模式到 PLAN，显示提示 |
| `/session` | `sessions` | UI_STATE | 列出历史会话（最近在前） |
| `/memory` | — | UI_STATE | 显示当前记忆索引（用户级 + 项目级） |
| `/permission` | `perm` | UI_STATE | 显示当前模式 + 已加载规则列表 |
| `/new` | — | UI_STATE | 开新会话（清空历史与消息区） |
| `/exit` | `quit`, `q` | UI_STATE | 同步会话存档后退出 |
| `/compact` | — | UI_STATE | 触发一次上下文压缩，显示结果 |
| `/do` | — | PROMPT | 切换回 DEFAULT，注入「按计划执行」提示并启动 Agent |
| `/review` | — | PROMPT | 注入代码审查提示（带可选范围参数）并启动 Agent |

## 模块交互

```
用户回车
  → ChatModel.submit()
      ├─ 输入空 / streaming 中 → 早返回
      ├─ 不以 "/" 开头 → 普通消息：加历史 + 同步 + startAgent()
      └─ 以 "/" 开头 → CommandParser.parse
            ├─ 未命中 → 显示 /help 引导（spec F2/AC3）
            └─ 命中 → CommandRegistry.lookup → handler.handle(ctx, args)
                      handler 通过 CommandContext 操作界面：
                        display / setMode / sendPrompt / quit …
      → submit 收尾：若 handler 请求了退出 → 返回 QuitMessage；
                      若请求了发提示 → startAgent()（返回 tick）；
                      否则 UpdateResult.from(this)
```

Tab 键（`handleKey` 的 `tab` 分支）：
```
输入以 "/" 开头
  → CommandRegistry.complete(前缀)
      ├─ 1 个候选 → 补全为 "命令名 "（唯一匹配直接补全）
      ├─ >1 个候选 → 显示候选列表（弹菜单），输入保持不动
      └─ 0 个候选 → 忽略
```

## 文件组织

```
src/main/java/com/novacode/command/
├── CommandType.java       — LOCAL / UI_STATE / PROMPT 枚举
├── CommandSpec.java       — 命令元数据记录
├── CommandHandler.java    — 处理函数式接口
├── CommandContext.java    — 界面控制接口 + TokenStats 记录
├── CommandParser.java     — 斜杠解析（Parsed 记录）
├── CommandRegistry.java   — 注册 + 冲突检测 + 查找 + 补全
└── BuiltinCommands.java   — 内置命令装配

src/main/java/com/novacode/ui/ChatModel.java（修改）
    — 实现 CommandContext；submit() 接入分流器；handleKey 加 tab 分支
src/main/java/com/novacode/context/ContextManager.java（修改）
    — 暴露 estimateCurrent(history)（/status 用）
src/main/java/com/novacode/permission/RuleEngine.java（修改）
    — 暴露 allRules()（/permission 用）
src/main/java/com/novacode/permission/PermissionFile.java（读取，不修改）
    — 供 /permission 展示已加载规则
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 命令包与 TEA `Command` 命名冲突 | 命令放独立包 `com.novacode.command`，不触碰 `tui.tea.Command` | 避免与 TEA 异步效果类型混淆 |
| 命令总数 | 10 高频 + `/new` + `/exit` + 隐藏 `/version` = 13 | spec 点名的 10 个是「高频」集合，非上限；`/new`、`/exit` 是既有功能必须保留 |
| `/session` 命名 | 主名 `/session`，`/sessions` 作别名 | spec 用单数；保留复数别名以兼容第 9 章已有行为 |
| `/do` 语义 | 切回 DEFAULT + 注入「按计划执行」提示 | 保留第 6 章已验证行为；spec「两个动词」只约束模式切换拆分 |
| `/exit` 归类 | UI_STATE，经 `CommandContext.quit()` | spec 接口「至少支持」允许扩展；统一迁入注册中心 |
| 冲突处理 | 未检查异常上抛 → 入口 catch → `System.exit(1)` | 满足「启动期 panic 退出」且复用 `App` 现有全局异常处理 |
| 补全「弹菜单」 | 多候选时显示候选列表，输入保持不动 | 本 TUI 无现成菜单控件；候选列表即「菜单」，用户继续输入收敛 |
| `/status` 上下文估算 | 复用 `ContextManager` 的锚定估算器 | 比字符法更准；暴露只读估算方法 |
| `/permission` 规则展示 | `RuleEngine` 暴露只读 `allRules()` | 规则本就在引擎里，无需额外存储 |
| `/review` 提示内容 | 代码写死 + 把可选参数拼进范围说明 | spec 明确「不做动态生成提示词」 |
