# 第 6 章：五层权限系统 Tasks

> 基于已批准 spec.md + plan.md。执行顺序：permission 包（T1-T8）→ Agent 接线（T9-T10）→ UI 接线（T11）→ 构建检查（T12）→ 临时无头验收（T13）→ 收尾（T14）。

## 完成状态（2026-08-10）

**T1-T14 全部完成。** 构建零警告、`mvn -DskipTests package` BUILD SUCCESS、jar 冒烟正常；`.tmp_test/PermissionVerify.java` 无头验收 58 项断言全部 PASS（已删除）。交付时向用户说明的两处有意偏差：

1. **黑名单不含 shutdown/reboot/poweroff/halt**：task.md T6 步骤 1 提到的「关机/断电」未纳入内置正则——spec.md 命名的黑名单类别是「递归强删根/家目录、写块设备、fork 炸弹、重定向覆盖磁盘设备、格式化文件系统」等，未含关机；纳入会造成手工测试的破坏性误伤。黑名单保持「启发式防御、明确不穷尽」的定位（spec 明示）。
2. **`Rule.of` 签名为 `of(String spec, boolean allow, String source)`**（单 spec 参数内解析工具名与模式段），非 task.md T3 写的 `of(friendlyName, spec, ...)`——二者等价，前者免调用方重复拼名。另：Rule 增加 `spec` 组件，deny 拒绝原因含规则原文（如 `规则(LOCAL) 拒绝: Write(target.txt)`）便于模型定位。

其余全部按 plan/task 执行，无其他偏差。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/permission/PermissionMode.java` | 四档模式矩阵 + next/parse/label |
| 新建 | `src/main/java/com/novacode/permission/Decision.java` | 最终裁决（ALLOW/DENY + reason） |
| 新建 | `src/main/java/com/novacode/permission/RuleTier.java` | 三级枚举（优先级序） |
| 新建 | `src/main/java/com/novacode/permission/Rule.java` | 规则记录 + glob→regex |
| 新建 | `src/main/java/com/novacode/permission/RuleEngine.java` | 三级合并裁决 + addRule |
| 新建 | `src/main/java/com/novacode/permission/PermissionFile.java` | YAML POJO（mode/allow/deny） |
| 新建 | `src/main/java/com/novacode/permission/PermissionConfig.java` | 配置产物（engine + defaultMode） |
| 新建 | `src/main/java/com/novacode/permission/RuleLoader.java` | 三层 YAML 加载 + 降级 |
| 新建 | `src/main/java/com/novacode/permission/Blacklist.java` | 内置高危命令正则 |
| 新建 | `src/main/java/com/novacode/permission/PathSandbox.java` | 项目根围栏（符号链接感知） |
| 新建 | `src/main/java/com/novacode/permission/AskContext.java` | HITL 渲染上下文 |
| 新建 | `src/main/java/com/novacode/permission/HitlOutcome.java` | HITL 决策枚举 |
| 新建 | `src/main/java/com/novacode/permission/PermissionPrompter.java` | HITL 回调接口 |
| 新建 | `src/main/java/com/novacode/permission/PermissionCancelledException.java` | 取消信号 |
| 新建 | `src/main/java/com/novacode/permission/PermissionEngine.java` | 五层流水线主编排 |
| 修改 | `src/main/java/com/novacode/agent/AgentEvent.java` | + PermissionAskEvent |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | 构造器 + executeBatched 闸门 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 模式状态/prompter/确认块/Shift+Tab/状态栏 |
| 修改 | `.gitignore` | 忽略本地配置层与运行产物 |

## T1: PermissionMode

**文件：** `src/main/java/com/novacode/permission/PermissionMode.java`
**依赖：** 无
**步骤：**
1. 枚举 `DEFAULT, ACCEPT_EDITS, PLAN, BYPASS_PERMISSIONS`。
2. `boolean allows(ToolCategory c)`：DEFAULT=只读✓；ACCEPT_EDITS=只读/写✓、命令✗；PLAN=只读✓、其余✗；BYPASS=全✓。返回 true=Allow，false=Ask（永不产 Deny）。
3. `PermissionMode next()`：DEFAULT→ACCEPT_EDITS→PLAN→BYPASS_PERMISSIONS→DEFAULT。
4. `static PermissionMode parse(String)`：`acceptedits`/`accept-edits`→ACCEPT_EDITS、`plan`→PLAN、`bypasspermissions`/`bypass`→BYPASS_PERMISSIONS、其余→DEFAULT。
5. `String label()`：`default`/`acceptEdits`/`plan`/`bypassPermissions`。

**验证：** `mvn compile` 通过；`parse("acceptedits")==ACCEPT_EDITS`、`next()` 循环正确（临时测试）。

## T2: Decision + RuleTier

**文件：** `src/main/java/com/novacode/permission/Decision.java`、`RuleTier.java`
**依赖：** 无
**步骤：**
1. `Decision(Verdict, reason)` record；`Verdict {ALLOW, DENY}`；静态 `allow()` / `deny(reason)`。
2. `RuleTier { LOCAL, PROJECT, USER }`（枚举序即优先级序，最高在前）。

**验证：** `mvn compile` 通过。

## T3: Rule

**文件：** `src/main/java/com/novacode/permission/Rule.java`
**依赖：** T2
**步骤：**
1. record `Rule(String friendlyName, Pattern pattern, boolean allow, String source)`。
2. `static Rule of(String friendlyName, String spec, boolean allow, String source)`：`spec` 取首个 `(` 与最后一个 `)` 切分得 pattern；无 `(` 则 pattern=`.*`（匹配该工具全部调用）；`isPath(name)`（非 Bash）决定 glob 语义。
3. `static Pattern globToRegex(String glob, boolean pathSemantics)`：`**`→`.*`；`*`→路径 `[^/\\\\]*` / 命令串 `.*`；`?`→路径 `[^/\\\\]` / 命令串 `.`；其余 `Pattern.quote`；首尾锚定。

**验证：** `mvn compile` 通过；临时断言 `globToRegex("src/**")` 匹配 `src/a/b.java`、不匹配 `docs/x`。

## T4: RuleEngine

**文件：** `src/main/java/com/novacode/permission/RuleEngine.java`
**依赖：** T2、T3
**步骤：**
1. 持有 `Map<RuleTier, List<Rule>>`。
2. `void addRule(RuleTier, Rule)`。
3. `Decision match(String friendlyName, String subject)`：按 LOCAL→PROJECT→USER 扫描；某层若有匹配：任一 deny 命中→DENY，否则有 allow 命中→ALLOW，该层即止；全层未命中→null。

**验证：** `mvn compile` 通过；临时断言同层 deny 优先于 allow、本地盖过项目。

## T5: PermissionFile + PermissionConfig + RuleLoader

**文件：** `src/main/java/com/novacode/permission/PermissionFile.java`、`PermissionConfig.java`、`RuleLoader.java`
**依赖：** T2、T4
**步骤：**
1. `PermissionFile`：public 字段 `String mode`、`List<String> allow`、`List<String> deny`（getter/setter）。
2. `PermissionConfig(RuleEngine engine, PermissionMode defaultMode)` record。
3. `RuleLoader.load(Path projectRoot, Path userHome)`：依次读用户级 `home/.novacode/permissions.yaml`、项目级 `root/.novacode/permissions.yaml`、本地级 `root/.novacode/permissions.local.yaml`；每层解析 `allow`→allow 规则、`deny`→deny 规则；`mode` 记录为候选 defaultMode（本地>项目>用户，首个非空生效，皆无 DEFAULT）；文件缺失→空；解析异常→catch 后跳过该层，不抛出。
4. `RuleLoader.load()` 默认 `load(Path.of("user.dir"), Path.of("user.home"))`。
5. Jackson YAML：`YAMLFactory` + 无命名策略的 `ObjectMapper`（key 已是小写）。

**验证：** `mvn compile` 通过；临时断言缺文件→空规则、非法 YAML→跳过且不抛异常、defaultMode 优先级正确。

## T6: Blacklist + PathSandbox

**文件：** `src/main/java/com/novacode/permission/Blacklist.java`、`PathSandbox.java`
**依赖：** 无
**步骤：**
1. `Blacklist.matches(String command)`：对命令串（小写化后）跑内置正则列表，命中返回模式标签（如 `黑名单: 递归强删根/家目录`），未命中返回 null。至少覆盖：递归强删根/家目录、写块设备、格式化文件系统、重定向覆盖磁盘设备、fork 炸弹、关机/断电、Windows 整盘删除/格式化。注意避免误伤 `rm -rf ./target`、`rm -rf src`。
2. `PathSandbox(Path root)`：root 规整 + `toRealPath()` 得 `rootReal`。
3. `check(Path target)`：绝对直接、相对按 root 解析 → `normalize()` → 若不存在沿父链找最近已存在祖先 → 祖先 `toRealPath()` → 拼回不存在后缀 → `startsWith(rootReal)` 判断；不满足返回违规说明，满足返回 null。root 本身视为在界内。

**验证：** `mvn compile` 通过；临时断言 `rm -rf /`、`rm -rf ~` 命中、`rm -rf ./target` 不命中；`/etc/passwd`、`../outside` 违规；含软链接逃逸违规；新建深层路径放行。

## T7: AskContext + HitlOutcome + PermissionPrompter + PermissionCancelledException

**文件：** `src/main/java/com/novacode/permission/` 下四个文件
**依赖：** 无
**步骤：**
1. `AskContext(String toolName, String argsPreview, String reason, String ruleName, String ruleSubject)` record。
2. `HitlOutcome {ALLOW_ONCE, ALLOW_FOREVER, DENY, CANCELLED}` enum。
3. `PermissionPrompter { HitlOutcome ask(AskContext ctx); }` 接口。
4. `PermissionCancelledException extends RuntimeException`。

**验证：** `mvn compile` 通过。

## T8: PermissionEngine

**文件：** `src/main/java/com/novacode/permission/PermissionEngine.java`
**依赖：** 全部前置
**步骤：**
1. 字段：blacklist / sandbox / rules / `Supplier<PermissionMode> modeSupplier` / prompter / `Path localRulesPath`。
2. `decide(Tool tool, Map<String,Object> args)` 五层流水线（见 plan.md「模块设计」）。未知工具直接 DENY。
3. 主题提取：按工具名 switch 得 friendlyName/subject/target（Bash 用 `command`；Read/Write/Edit 用 `file_path`；Glob/Grep 用 `path` 默认 `.`）。文件类 subject=项目内相对路径（`/` 分隔），target=规整绝对路径。
4. HITL：Ask 时若 prompter==null 降级 DENY；ALLOW_FOREVER 写本地 YAML（`PermissionFile.allow.add(rule)` + 重建，文件不存在新建）并 `addRule(LOCAL, ...)`；CANCELLED 抛异常。
5. 参数预览：Bash 显示 `command=<命令串>`（截断），文件类显示首个 `key=value`。

**验证：** `mvn compile` 通过；临时断言黑名单/沙箱/规则/模式矩阵/HITL 全路径。

## T9: AgentEvent + PermissionAskEvent

**文件：** `src/main/java/com/novacode/agent/AgentEvent.java`
**依赖：** T7
**步骤：**
1. 新增 `record PermissionAskEvent(AskContext context, CompletableFuture<HitlOutcome> future) implements AgentEvent {}`。
2. 加 import：`com.novacode.permission.*`、`java.util.concurrent.CompletableFuture`。

**验证：** `mvn compile` 通过（sealed switch 其余分支不涉及）。

## T10: Agent 接线

**文件：** `src/main/java/com/novacode/agent/Agent.java`
**依赖：** T8、T9
**步骤：**
1. 构造器加 `PermissionEngine permissionEngine` 参数。
2. `executeBatched` 重构：
   - 只读批量：先同步 `decide` 全部（只读永不 Ask），被拒的直接设错误结果，只对放行发 `ToolUseEvent` + 并发执行；批结束后对被拒的补发 `ToolResultEvent`（isError）。
   - 串行（写/命令）：先 `decide`；Deny→`Error: 权限拒绝: <reason>` 错误结果 + 补发事件 + continue；`PermissionCancelledException`→剩余填 `（已取消）` + 返回 `completed=false`；Allow→原逻辑。
3. 被拒/取消结果仍走 `ChatMessage.toolResult(toolId, output)` 配对，Loop 不中断（除取消）。

**验证：** `mvn compile` 通过；确认 `SmokeTest.java` 不受影响（ChatModel 构造时注入引擎）。

## T11: ChatModel 接线

**文件：** `src/main/java/com/novacode/ui/ChatModel.java`
**依赖：** T10
**步骤：**
1. 字段：`PermissionMode currentMode`（初值 `RuleLoader.load().defaultMode()`）、`CompletableFuture<HitlOutcome> pendingAskFuture`、`AskContext pendingAsk`、`int askSelection`。删除 `planMode`。
2. 构造器：`RuleLoader.load()` → 建 `RuleEngine`；`new PermissionEngine(..., () -> currentMode, this, <cwd>/.novacode/permissions.local.yaml)`；`new Agent(client, registry, protocol, permissionEngine)`。
3. `ChatModel implements PermissionPrompter`：`ask(ctx)` 投递 `PermissionAskEvent` + `future.join()`。
4. `submit()`：`/plan`→`currentMode=PLAN`；`/do`→`currentMode=DEFAULT` + 注入执行指令；`startAgent()` 传 `currentMode == PLAN`。
5. `handleKey`：顶部 `if (pendingAskFuture != null) return handleAskKey(kp)`；`case "shift+tab"`→`currentMode = currentMode.next()`。
6. `handleAskKey`：up/down 移动选择；enter 按选中项 resolve；1/2/3 直选；esc/ctrl+c→CANCELLED。`resolveAsk(outcome)`：清空 pendingAsk 状态并 `future.complete(outcome)`。
7. `pollAgent`：`case PermissionAskEvent`→记录 pendingAsk/pendingAskFuture/askSelection=0。
8. `view`：pendingAsk 时在内容末尾追加确认块（`── 权限确认 ──` + 工具/参数/原因 + 三选项高亮 `»` + 操作提示），并按块行数增大 `bottomH`；状态栏左侧 `Styles.yellow("[" + currentMode.label() + "]")` 替换 provider 名与 `[PLAN]` 标记。

**验证：** `mvn compile` 通过；`SmokeTest.main` 正常（view 含模式标签）。

## T12: 构建检查

**文件：** 无
**依赖：** T11
**步骤：**
1. `rm -rf target/classes target/maven-status target/generated-sources && mvn compile` → 无警告（`-Xlint:all`）。
2. `mvn -q -DskipTests package`（避开 jar 锁用非 clean）→ BUILD SUCCESS，产物 `target/novacode-1.0.0.jar`。

**验证：** 无 "WARNING" 输出；jar 生成；`java -jar target/novacode-1.0.0.jar` 启动冒烟（无 crash log）。

## T13: 临时无头验收（验证后删除）

**文件：** `.tmp_test/PermissionVerify.java`（临时）
**依赖：** T12
**步骤：**
1. FakeClient 实现 `LlmClient`：脚本化重放工具调用序列 + 记录收到历史。
2. FakePrompter 实现 `PermissionPrompter`：按脚本返回 ALLOW_ONCE/ALLOW_FOREVER/DENY/CANCELLED。
3. 断言覆盖（对照 checklist 关键项）：黑名单硬拦截（bypass 也拦）、沙箱围栏（/etc/passwd、../outside、软链接逃逸、新建深层路径放行）、规则精确/glob/友好名路由、三级优先级 + 同层 deny 优先、配置降级、模式矩阵逐档逐类、流水线短路与跳层放行、HITL 三选一 + 永久写本地 + 取消安全、只读并发不退化（批量只读不 Ask）、Agent 整链路回灌不中断（脚本：Bash(git push) deny→模型收错误→继续）。
4. 编译运行：`javac -cp target/classes;<deps> .tmp_test/PermissionVerify.java` + `java -cp ...`。全部通过后删除 `.tmp_test/`。

**验证：** 全部断言通过（先有证据再报状态）。

## T14: 收尾

**文件：** `.gitignore`
**依赖：** T13
**步骤：**
1. `.gitignore` 增加：`target/`、`.novacode/permissions.local.yaml`、`.novacode/plans/`、`nova_error.log`、`nova_cache.log`、`nova_crash.log`。
2. 确认源码树无临时文件残留（.tmp_test 已删）。

**验证：** `git check-ignore .novacode/permissions.local.yaml`（若有 git）或人工确认条目存在；`.tmp_test/` 不存在。

## 执行顺序

```
T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8
                                      ↘
T9 → T10 ──► T11 ──► T12 ──► T13 ──► T14
```

（T1-T8 均为 permission 包内部自底向上；T9-T10 必须等 T8；T11 等 T10；T12-T14 依次。）
