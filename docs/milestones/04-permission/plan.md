# 第 6 章：五层权限系统 Plan

> 基于已批准 spec.md。实现语言 Java 21，`-Xlint:all` 无警告，沿用项目既有代码风格（record、text block、无多余注释）。

## 架构概览

新增 `com.novacode.permission` 包（判定核心），并在现有编排层接线（不改 provider 适配层）。

```
                     ┌────────────────────────────────────────────┐
                     │           PermissionEngine.decide()        │
 一次工具调用         │  ① 黑名单(仅命令类) ② 沙箱(仅文件类)          │
 ──► 真正执行前 ────► │  ③ 规则引擎(三级) ④ 模式兜底 ⑤ 人在回路(HITL)│ ──► Allow / Deny
                     └────────────────────────────────────────────┘
```

- **判定核心** `PermissionEngine`：五层流水线主编排。任一层给出终值即短路；Ask 由 `PermissionPrompter` 阻塞式解析（允许本次 / 永久 / 拒绝本次 / 取消）。
- **黑名单** `Blacklist`：内置正则，仅对命令执行类工具的命令串匹配；命中即 Deny，不可被任何模式/规则放开。
- **沙箱** `PathSandbox`：仅对文件类工具的目标路径做「规整为绝对 → 最近已存在祖先解析符号链接 → 前缀比对项目根」。
- **规则** `Rule` / `RuleTier` / `RuleEngine`：`工具名(模式)` 的 allow/deny 规则，三级合并（本地 > 项目 > 用户，就近命中即止；同层 deny 优先）。
- **模式** `PermissionMode`：default / acceptEdits / plan / bypassPermissions 四档，按只读/文件写/命令执行给出 Allow 或 Ask 兜底；运行时可切换。
- **人在回路**：`PermissionPrompter` 接口（由 ChatModel 实现）+ `AskContext`（渲染数据）+ `HitlOutcome`（决策结果）+ `PermissionCancelledException`（取消信号）。
- **配置** `RuleLoader` + `PermissionFile`：三层 YAML 加载，缺失即空、格式非法降级为空集、绝不让引擎构造失败。

接线点：
- `Agent.executeBatched`：每个工具调用执行前先过 `PermissionEngine.decide`；Deny 产出结构化错误结果回灌、Loop 继续；取消干净短路。
- `AgentEvent` 新增 `PermissionAskEvent`：携带 `AskContext` + `CompletableFuture<HitlOutcome>`。
- `ChatModel`：实现 `PermissionPrompter`（代理线程阻塞于 future.join，UI 线程渲染确认块并回填决策）、持有 `currentMode`、处理 Shift+Tab、状态栏常驻模式名。

## 核心数据结构

### 判定结果

```java
// 五层流水线最终值：Allow 或 Deny（Ask 在流水线内部被 HITL 解析掉，不外泄）
public record Decision(Verdict verdict, String reason) {
    public enum Verdict { ALLOW, DENY }
    public static Decision allow() { return new Decision(Verdict.ALLOW, ""); }
    public static Decision deny(String reason) { return new Decision(Verdict.DENY, reason); }
}
```

### 权限模式

```java
public enum PermissionMode {
    DEFAULT, ACCEPT_EDITS, PLAN, BYPASS_PERMISSIONS;
    // 规则未命中时的兜底：返回「是否放行」（false = Ask）。只产 Allow/Ask，绝不产 Deny。
    public boolean allows(ToolCategory c) { /* 见矩阵 */ }
    public PermissionMode next() { /* Shift+Tab 循环顺序 */ }
    public static PermissionMode parse(String s) { /* YAML 解析，非法→DEFAULT */ }
    public String label() { /* 状态栏显示名 */ }
}
```

矩阵：`DEFAULT: READ✓ / 其他✗`；`ACCEPT_EDITS: READ✓ WRITE✓ COMMAND✗`；`PLAN: READ✓ / 其他✗`；`BYPASS: 全✓`。

### 规则

```java
public record Rule(String friendlyName, Pattern pattern, boolean allow, String source) {
    // "Tool(pattern)" → 取第一个'('与最后一个')'切分；无括号视为匹配该工具全部调用（pattern=.*）
    public static Rule of(String name, String spec, boolean allow, String source) { ... }
    public static Pattern globToRegex(String glob, boolean pathSemantics) { ... }
}

public enum RuleTier { LOCAL, PROJECT, USER }  // 优先级序即枚举序
```

`globToRegex`：`**` → `.*`；`*` → 命令串 `.*` / 路径 `[^/]*`；`?` → 命令串 `.` / 路径 `[^/]`；其余字符 `Pattern.quote`。

### 规则引擎

```java
public class RuleEngine {
    // tiers: LOCAL → PROJECT → USER；同层 deny 优先于 allow；就近命中即止
    public Decision match(String friendlyName, String subject)  // 命中→ALLOW/DENY；未命中→null
    public void addRule(RuleTier tier, Rule rule)               // 供「永久放行」即时生效
}
```

### 配置

```java
public class PermissionFile {          // Jackson YAML POJO
    public String mode;                // 可选默认模式
    public List<String> allow;         // ["Bash(git *)", ...]
    public List<String> deny;
}

public record PermissionConfig(RuleEngine engine, PermissionMode defaultMode) {}

public class RuleLoader {
    // 用户级 ~/.novacode/permissions.yaml；项目级 <cwd>/.novacode/permissions.yaml；本地级 <cwd>/.novacode/permissions.local.yaml
    public static PermissionConfig load()                                  // 默认 user.dir + user.home
    public static PermissionConfig load(Path projectRoot, Path userHome)   // 测试注入
}
```

### 黑名单

```java
public class Blacklist {
    // 内置正则，不可配置。命令串 → 命中的模式标签或 null。
    public String matches(String command)
}
```

### 沙箱

```java
public class PathSandbox {
    private final Path root;      // 规整后的项目根
    private final Path rootReal;  // 解析符号链接后的项目根
    // 目标路径 → 违规原因或 null。相对路径按项目根解析；不存在目标解析最近已存在祖先再比对。
    public String check(Path target)
}
```

### 人在回路

```java
public record AskContext(String toolName, String argsPreview, String reason,
                         String ruleName, String ruleSubject) {}
public enum HitlOutcome { ALLOW_ONCE, ALLOW_FOREVER, DENY, CANCELLED }
@FunctionalInterface
public interface PermissionPrompter { HitlOutcome ask(AskContext ctx); }
public class PermissionCancelledException extends RuntimeException {}
```

### 判定核心

```java
public class PermissionEngine {
    private final Blacklist blacklist;
    private final PathSandbox sandbox;
    private final RuleEngine rules;
    private final Supplier<PermissionMode> modeSupplier;   // 运行时读当前模式（跨轮保持）
    private final PermissionPrompter prompter;             // null → Ask 直接降级为 Deny（安全默认）
    private final Path localRulesPath;                     // 「永久放行」写入位置

    public static PermissionEngine create(Blacklist, PathSandbox, RuleEngine,
            Supplier<PermissionMode>, PermissionPrompter, Path localRulesPath)

    // ① 未知工具→DENY ② 黑名单(命令类) ③ 沙箱(文件类) ④ 规则 ⑤ 模式兜底 ⑥ HITL
    public Decision decide(Tool tool, Map<String, Object> args)
}
```

## 模块设计

### PermissionEngine —— 五层流水线
**职责**：单次工具调用的最终裁决。**对外接口**：`decide(Tool, Map)`。**依赖**：Blacklist / PathSandbox / RuleEngine / PermissionMode / PermissionPrompter。

判定顺序（任一终值即短路）：
1. `tool == null` → DENY（未注册工具，安全默认，不静默放行）。
2. 命令执行类 → `blacklist.matches(command)`，命中 → DENY。
3. 文件类 → 提取目标路径 → `sandbox.check(abs)`，违规 → DENY。
4. `rules.match(friendlyName, subject)`：ALLOW → 放行；DENY → 拒绝；null → 进第 5 步。
5. `mode.allows(category)`：true → ALLOW；false → 第 6 步。
6. HITL：`prompter.ask(ctx)`。ALLOW_ONCE → ALLOW；ALLOW_FOREVER → 写本地规则 + ALLOW；DENY → DENY；CANCELLED → 抛 `PermissionCancelledException`。

**主题提取**（`friendlyName` + `subject` + `target`）：
- Bash → friendly=`Bash`，subject=命令串，target=null。
- ReadFile/WriteFile/EditFile → friendly=`Read`/`Write`/`Edit`，target=路径（相对按项目根解析、规整），subject=项目内相对路径（转 `/`；在项目外则用绝对路径串，沙箱会拦）。
- Glob/Grep → friendly=`Glob`/`Grep`，target=搜索根 `path`（默认 `.`），subject=相对搜索根。

**永久放行写入**：把 `friendlyName(subject)` 追加到本地级 YAML 的 `allow` 列表（文件不存在则新建，非法则重建），并同步 `RuleEngine.addRule(LOCAL, ...)` 即时生效。精确匹配、不自动泛化。

### Blacklist —— 内置高危命令正则
**职责**：仅命令执行类；启发式防御，不追求穷尽；不可配置。**对外接口**：`matches(String)`。

覆盖（示例，正则取命令串小写匹配）：递归强删根/家目录（`rm -rf /`、`rm -rf ~`、`rm -rf /home`）、写块设备（`dd of=/dev/...`）、格式化文件系统（`mkfs`）、重定向覆盖磁盘设备（`> /dev/sd*`）、fork 炸弹（`:(){ :|:& };:`）、关机/断电（shutdown/poweroff/halt/reboot）、Windows 整盘删除/格式化（`rd /s /q C:\`、`format C:`）。

### PathSandbox —— 项目根围栏
**职责**：文件类工具逃逸拦截。**对外接口**：`check(Path)`。

算法：相对路径按项目根解析 → `normalize()` → 若目标不存在，沿父链找**最近已存在祖先** → 该祖先 `toRealPath()`（解析符号链接）→ 拼接回不存在的后缀 → 与 `rootReal` 前缀比对；不在项目内即违规。只读与写入一视同仁。Bash 不走沙箱。

### RuleEngine / Rule / RuleTier —— 三级规则
**职责**：三级合并裁决。**对外接口**：`match(friendlyName, subject)`、`addRule(tier, rule)`。

### RuleLoader / PermissionFile —— 三层配置加载
**职责**：读三层 YAML → `PermissionConfig`。**对外接口**：`load()` / `load(root, home)`。

降级：文件缺失 → 该层空规则集；文件格式非法 / 解析异常 → 跳过该文件、其余正常加载、不抛运行时异常。`defaultMode` 按 本地 > 项目 > 用户 取首个非空，皆无 → DEFAULT。

### PermissionMode —— 四档模式
**职责**：规则未命中时的兜底矩阵 + 运行时切换。**对外接口**：`allows(category)`、`next()`、`parse(label)`、`label()`。

### ChatModel（接线）
**新增职责**：
- 持有 `PermissionMode currentMode`（默认取配置 defaultMode，未配置 DEFAULT），替换原 `planMode` 布尔（plan 行为 = `currentMode == PLAN`）。
- 实现 `PermissionPrompter.ask`：向 `agentQueue` 投递 `PermissionAskEvent`，阻塞于 `future.join()`；由 UI 线程的确认块回填。
- `handleKey`：`shift+tab` 循环切模式；`pendingAsk` 激活时拦截 `up/down/enter/1/2/3/esc/ctrl+c`。
- `pollAgent`：收到 `PermissionAskEvent` 记录 `pendingAsk` + `pendingAskFuture`，渲染确认块。
- `view`：pendingAsk 时底部渲染多行确认块（工具/参数/原因 + 三选项高亮）；状态栏左侧显示当前模式（替换 provider 名）。

### Agent（接线）
**新增职责**：
- 构造器新增 `PermissionEngine` 参数。
- `executeBatched`：每个调用执行前 `permissionEngine.decide(tool, args)`。
  - 只读批量：同步预判全部决定（只读永不 Ask、不阻塞）→ 只对放行发 `ToolUseEvent` 并并发执行，被拒的直接产出错误结果并补发事件 → 并发不退化。
  - 串行（写/命令）：先判定再执行；Ask 时阻塞于人在回路；Deny 产出 `Error: 权限拒绝[来源]: reason` 回灌；取消时剩余调用填 `（已取消）`、返回 `completed=false`。
- 被拒/取消结果仍按调用 ID 与历史配对（`ChatMessage.toolResult(toolId, output)` 机制不变）。

## 模块交互

```
LLM 请求工具调用 ──► Agent.executeBatched
                          │ 对每个 call：
                          ▼
               PermissionEngine.decide(tool, args) ──► Deny → ToolResult.error("权限拒绝[来源]: reason")
                          │ Allow                           → 正常 execute
                          │ Ask（仅写/命令）
                          ▼
               prompter.ask(ctx)  ── PermissionAskEvent ──► ChatModel.pollAgent → 渲染确认块
               （代理线程阻塞）      ◄── future.complete ────── handleKey 1/2/3/enter/esc
                          │ ALLOW_ONCE/ALLOW_FOREVER → 放行
                          │ DENY → 拒绝回灌
                          │ CANCELLED → throw → 剩余填"（已取消）"、Loop 干净退出
```

模式切换：`handleKey(shift+tab)` → `currentMode.next()`；`/plan` → `PLAN`；`/do` → `DEFAULT` 并注入执行指令。Agent 每次 `decide` 经 `modeSupplier` 读当前模式 → 切换即时生效、跨轮保持。plan 档经 `startAgent` 的 `planMode` 参数继续只暴露只读工具 + 计划提醒（ch04/ch05 行为）。

## 文件组织

```
project/
├── src/main/java/com/novacode/
│   ├── permission/                        # 新增包
│   │   ├── PermissionMode.java
│   │   ├── Decision.java
│   │   ├── Rule.java
│   │   ├── RuleTier.java
│   │   ├── RuleEngine.java
│   │   ├── PermissionFile.java
│   │   ├── RuleLoader.java
│   │   ├── PermissionConfig.java
│   │   ├── Blacklist.java
│   │   ├── PathSandbox.java
│   │   ├── AskContext.java
│   │   ├── HitlOutcome.java
│   │   ├── PermissionPrompter.java
│   │   ├── PermissionCancelledException.java
│   │   └── PermissionEngine.java
│   ├── agent/
│   │   ├── AgentEvent.java                # 修改：+ PermissionAskEvent
│   │   └── Agent.java                     # 修改：构造器 + executeBatched 闸门
│   └── ui/
│       └── ChatModel.java                 # 修改：模式状态、prompter、确认块、Shift+Tab、状态栏
├── .gitignore                             # 修改：忽略本地配置层
```

配置文件（运行期，非源码）：
- `~/.novacode/permissions.yaml`（用户级）
- `.novacode/permissions.yaml`（项目级，可提交）
- `.novacode/permissions.local.yaml`（本地级，gitignore，永久放行写入目标）

```yaml
mode: default
allow:
  - "Bash(git *)"
deny:
  - "Bash(git push)"
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 判定返回值 | `Decision(Verdict, reason)`，Ask 在引擎内部被 HITL 解析 | 五层对外只暴露 Allow/Deny，编排层简单；reason 携带来源标签 |
| HITL 阻塞机制 | 代理线程阻塞于 `CompletableFuture.join()`，UI 线程回填 | 虚拟线程阻塞零成本；事件经现有 `agentQueue` 通道，不改 TUI 事件机制 |
| 取消信号 | `PermissionCancelledException` 从 `decide` 抛出，`executeBatched` 捕获填「已取消」 | 复用既有 `completed=false` 取消路径，历史角色交替合法 |
| 只读并发不退化 | 批量只读先同步预判（只读永不 Ask）再并发执行 | 满足 N3：权限检查不把只读串行化 |
| 模式读取 | `Supplier<PermissionMode>`（ChatModel 的 currentMode） | 切换即时生效、跨轮保持，引擎不持态 |
| 永久放行写入 | Jackson YAML 追加本地层 allow + 内存规则同步 | 同一配置格式读写；`MINIMIZE_QUOTES` 保持可读 |
| 规则解析 | 首 `(` 尾 `)` 切分 + glob→regex | 容忍命令串内含 `)`；命令串 `**`≡`*`，路径 `**` 跨目录 |
| 未知工具 | 引擎直接 DENY（不静默放行） | 满足 N7/AC15 |
| prompter 为空 | Ask 降级为 Deny | 无头/异常路径安全默认，不悬挂 |
| 模式矩阵表达 | 单布尔 `allows()`（true=Allow false=Ask） | 矩阵值域 {Allow, Ask}，无需三值 |
