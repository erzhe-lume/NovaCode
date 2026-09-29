# 第 14 章 Git Worktree 隔离 Plan

## 架构概览

新增包 `com.novacode.worktree`，承载 worktree 的纯逻辑（创建/删除/校验/改动检测/清理/会话），
与「子 Agent 委派」解耦。子 Agent 侧（`com.novacode.subagent`）通过一个 `WorktreeService` 门面
在派发前后做 enter/exit，并把 cwd + 沙箱根注入工具与权限引擎。

核心思路（对齐 spec F5）：**不 chdir**。每个工具实例持有显式 `cwd` 字段（默认 `user.dir`），
文件工具把相对路径基于 `cwd` 解析并规范化为绝对路径；Bash 用 `cwd` 作为进程工作目录；
`FileStateCache` 已按绝对路径做 key，因此目录切换天然隔离、无需清缓存。

```
AgentTool.execute(spec)
  └─ spec.isolation == WORKTREE ?
        WorktreeService.enter(name)  → Enter(cwd, notice)
           ├─ SlugValidator.validate(name)
           ├─ AgentWorktree.create  → Result(worktreePath, branch, headCommit)
           │     ├─ 目录已存在 → fast-resume（只读 .git 指针 + HEAD，不跑 git）
           │     └─ 否则 → WorktreeManager.create(git worktree add -B)
           ├─ PostCreationSetup.setup(worktree, gitRoot)
           └─ AgentWorktree.buildNotice(Result, originalCwd)
        : null
  ├─ ToolFilter.filterForAgent(source, spec, async, cwd)  → 注入 cwd 的全新工具实例
  ├─ SubAgentRunner.buildAgent(registry, client, mode, maxTurns, sandboxRoot)
  │     └─ PermissionEngine.forSubAgent(mode).withSandbox(new PathSandbox(sandboxRoot))
  ├─ runner.run(agent, history, systemPrompt + notice, ...)
  └─ finally: WorktreeService.exit(Enter)
        └─ WorktreeChanges.hasChanges(cwd, headCommit) ? 保留 : WorktreeManager.remove
```

## 核心数据结构

### `Isolation`（`com.novacode.subagent`）
```java
public enum Isolation {
    NONE, WORKTREE;
    public static Isolation parse(String s); // null/空/"none"→NONE；"worktree"→WORKTREE；其余→NONE
}
```

### `SubAgentSpec`（扩展第 13 章）
新增第 9 个组件 `Isolation isolation`，并提供 8 参便捷构造器（`isolation=NONE`）以保持既有调用点不变。
```java
record SubAgentSpec(String name, String description, List<String> tools, List<String> disallowedTools,
        String systemPrompt, int maxTurns, String model, PermissionMode permissionMode,
        Isolation isolation)
```

### `SlugValidator`
```java
final class SlugValidator {
    static final int MAX_LENGTH = 64;
    static final Pattern VALID_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    static String validate(String name);   // null = 通过；否则返回错误信息
    static String flatten(String name);    // "/" → "+"
    static String branchName(String name); // "worktree-" + flatten
}
```
校验规则：整体非空且 ≤64；以 `/` 分段，每段非空、不等于 `.`/`..`、匹配 `VALID_SEGMENT`；
拒绝前导 `/`（绝对路径）。

### `WorktreeManager.WorktreeInfo`
```java
record WorktreeInfo(String branch, String path, String headCommit) {}
```
`WorktreeManager` 封装 git CLI（`ProcessBuilder`）：
```java
final class WorktreeManager {
    String detectGitRoot(Path start);                 // git rev-parse --show-toplevel
    WorktreeInfo create(String branch, Path targetDir); // git worktree add -B <branch> <target>
    void remove(Path targetDir);                       // git worktree remove --force + git branch -D
    List<WorktreeInfo> list();                          // git worktree list --porcelain 解析
    String currentBranch(Path dir);                     // git rev-parse --abbrev-ref HEAD
    boolean hasUpstream(Path dir);                      // git rev-parse --abbrev-ref --symbolic-full-name @{u}
}
```

### `AgentWorktree`
```java
final class AgentWorktree {
    record Result(Path worktreePath, String worktreeBranch, String headCommit, Path gitRoot) {}
    static Result create(Path gitRoot, String name, Path parentDir); // 含 fast-resume
    static void remove(Result r);
    static String buildNotice(Result r, Path originalCwd);
    static String readHead(Path worktreePath); // 只读 .git 指针文件 + HEAD + ref，不跑 git
}
```

### `WorktreeChanges`
```java
final class WorktreeChanges {
    static boolean hasLocalChanges(Path dir);            // git status --porcelain 非空（fail-closed）
    static boolean hasNewCommits(Path dir, String base); // git rev-list --count <base>..HEAD > 0（fail-closed）
    static boolean hasChanges(Path dir, String base);    // 二者或
}
```

### `PostCreationSetup`
```java
final class PostCreationSetup {
    static void setup(Path worktreePath, Path gitRoot); // 四步，逐步容错，失败仅记录
}
```
- 复制 `.novacode/settings.local.json`（存在才复制）。
- 若 `gitRoot/.husky` 存在，`git -C <worktree> config core.hooksPath .husky`。
- 读 `.novacode/worktree-symlinks.txt`（每行一个相对目录），对存在于 `gitRoot` 的目录在
  worktree 内建符号链接（Windows 下先试 `Files.createSymbolicLink`，失败降级 `mklink /J`，再失败跳过）。
- 读 `.worktreeinclude`（每行一个相对路径），把对应文件从 `gitRoot` 复制到 worktree。

### `StaleCleanup`
```java
final class StaleCleanup {
    static final Pattern EPHEMERAL = Pattern.compile("^agent-[0-9a-f]{8}$");
    static boolean isEphemeral(Path dir);
    static List<Path> cleanup(Path parentDir, long maxAgeMs); // 三层过滤
    static void startCleanupLoop(Path parentDir, long maxAgeMs, long intervalMs); // 可选守护
}
```
三层：① 目录名匹配临时名模式 → ② mtime 超过 maxAgeMs → ③ `!hasLocalChanges`（fail-closed）。
只对通过三层的目录执行 `WorktreeManager.remove`。

### `WorktreeService`（子 Agent 隔离门面）
```java
public final class WorktreeService {
    public record Enter(AgentWorktree.Result result, String notice) {
        public Path cwd() { return result.worktreePath(); }
        public String headCommit() { return result.headCommit(); }
    }
    public WorktreeService(Path gitRoot);              // parentDir = gitRoot/.novacode/worktrees
    public Enter enter(String name);                   // 校验→create→post-setup→notice
    public boolean exit(Enter e);                      // 有改动→保留(false)；无改动→删除(true)
    public String newEphemeralName();                  // "agent-" + 8 位随机 hex
}
```

### `WorktreeSession` / `WorktreeSessionStore`
```java
record WorktreeSession(Path originalCwd, Path worktreePath, String worktreeName,
        String worktreeBranch, String originalBranch, String originalHeadCommit,
        String sessionId, long creationDurationMs) {}
final class WorktreeSessionStore {
    Path defaultPath(Path gitRoot); // gitRoot/.novacode/worktree_session.json
    void save(Path storePath, WorktreeSession s);
    WorktreeSession load(Path storePath);
    void clear(Path storePath);
}
```
用于记录当前 enter 的 worktree 会话（供清理层跳过活动会话、供诊断），持久化为 JSON。

## 模块设计

### 工具 cwd 注入（改 `com.novacode.tool.impl`）
六个内置工具（ReadFile/WriteFile/EditFile/Glob/Grep/Bash）各加 `Path cwd` 字段（默认
`Path.of(System.getProperty("user.dir"))`）与 `void setCwd(Path)`。文件工具新增私有
`resolvePath(String)`：绝对路径原样、相对路径 `cwd.resolve(...)`，随后 `.normalize()`。
Glob/Grep 的 `path` 参数、Bash 的 `ProcessBuilder.directory` 同样基于 cwd。

### `ToolFilter` 扩展（改 `com.novacode.subagent`）
新增带 cwd 的重载：
```java
static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec, boolean isAsync, Path cwd);
```
`freshBuiltin` 重建实例后调用 `setCwd(cwd)`（cwd 为 null 时保持默认）。原两个重载委托到新签名。

### `PermissionEngine.withSandbox`（改 `com.novacode.permission`）
```java
public PermissionEngine withSandbox(PathSandbox sandbox); // 共享其余字段，仅换沙箱根
```

### `SubAgentRunner.buildAgent` 重载（改 `com.novacode.subagent`）
```java
public Agent buildAgent(ToolRegistry r, LlmClient c, PermissionMode m, int maxTurns);            // 委托
public Agent buildAgent(ToolRegistry r, LlmClient c, PermissionMode m, int maxTurns, Path sandboxRoot);
```
`sandboxRoot != null` 时用 `withSandbox(new PathSandbox(sandboxRoot))`。

### `AgentTool` 编排（改 `com.novacode.subagent`）
`runSync`/`runAsync` 在派发前判断 `spec.isolation() == WORKTREE`：
- 若为 worktree，`enter` 得到 cwd + notice；否则 cwd=null、notice=null。
- 用 cwd 过滤工具、用 cwd 做沙箱根、把 notice 追加到 system prompt。
- `finally` 中 `exit`（有改动保留、无改动删除）。后台路径在 worker 内部 enter/exit。

### `ChatModel` 接线（改 `com.novacode.ui`）
构造 `WorktreeService(projectRoot)` 并通过 `agentTool.setWorktreeService(...)` 注入。

## 模块交互

1. `ChatModel` 构造 `WorktreeService` → 注入 `AgentTool`。
2. 主 Agent 请求 `Agent` 工具 → `AgentTool.execute` → 命中 `isolation: worktree` 角色。
3. `AgentTool` 调 `WorktreeService.enter` → `SlugValidator` → `AgentWorktree.create`（必要时
   `WorktreeManager.create`）→ `PostCreationSetup.setup` → `buildNotice`。
4. `AgentTool` 用 `Enter.cwd()` 调 `ToolFilter.filterForAgent(..., cwd)` + `SubAgentRunner.buildAgent(..., cwd)`。
5. `runner.run` 执行子 Agent，工具基于 cwd 解析路径、沙箱根为 cwd。
6. `finally` 调 `WorktreeService.exit` → `WorktreeChanges.hasChanges` → 保留或 `WorktreeManager.remove`。
7. 后台守护（可选）周期性 `StaleCleanup.cleanup` 清过期临时目录。

## 文件组织

```
src/main/java/com/novacode/
├── worktree/
│   ├── SlugValidator.java
│   ├── WorktreeManager.java
│   ├── WorktreeChanges.java
│   ├── AgentWorktree.java
│   ├── PostCreationSetup.java
│   ├── StaleCleanup.java
│   ├── WorktreeService.java
│   ├── WorktreeSession.java
│   └── WorktreeSessionStore.java
├── subagent/
│   ├── Isolation.java            （新增）
│   ├── SubAgentSpec.java          （改：isolation 组件 + 便捷构造）
│   ├── AgentLoader.java           （改：解析 isolation）
│   ├── ToolFilter.java            （改：cwd 重载）
│   ├── SubAgentRunner.java        （改：buildAgent 重载）
│   └── AgentTool.java             （改：worktree 编排 + setWorktreeService）
├── permission/
│   └── PermissionEngine.java      （改：withSandbox）
├── tool/impl/
│   ├── ReadFileTool.java          （改：cwd）
│   ├── WriteFileTool.java         （改：cwd）
│   ├── EditFileTool.java          （改：cwd）
│   ├── GlobTool.java              （改：cwd）
│   ├── GrepTool.java              （改：cwd）
│   └── BashTool.java              （改：cwd）
└── ui/
    └── ChatModel.java             （改：接线 WorktreeService）
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| cwd 传递方式 | 工具实例字段 `setCwd`（非 chdir/ThreadLocal） | 显式、并发安全、与「每子 Agent 一份全新工具实例」的既有机制（第 13 章 ToolFilter）契合 |
| worktree 目录位置 | `gitRoot/.novacode/worktrees/` | 仓库内不被跟踪的位置，与 NovaCode 的 `.novacode` 约定一致 |
| 临时目录命名 | `agent-<8位hex>` | 与 StaleCleanup 临时名模式匹配，便于安全清理 |
| 改动检测 | `status --porcelain` + `rev-list --count <base>..HEAD` | base 为创建时 head，准确区分「本次 worktree 新增的提交」与「无上游」；fail-closed |
| fast-resume | 读 `.git` 指针文件 + HEAD + ref，不跑 git | 满足「目录已存在只读文件系统」，且避免额外进程 |
| 软链降级 | 符号链接 → `mklink /J` → 跳过 | Windows 无管理员时符号链接失败，junction 可用 |
| 沙箱根 | 每子 Agent 用 cwd 重建 `PathSandbox` | 子 Agent 只能碰自己的 worktree，不能碰主目录/兄弟 worktree |
