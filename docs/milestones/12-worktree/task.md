# 第 14 章 Git Worktree 隔离 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/worktree/SlugValidator.java` | 目录名校验 + flatten + branchName |
| 新建 | `src/main/java/com/novacode/worktree/WorktreeManager.java` | git CLI 封装（create/remove/list/detectRoot/branch/upstream） |
| 新建 | `src/main/java/com/novacode/worktree/WorktreeChanges.java` | fail-closed 改动检测 |
| 新建 | `src/main/java/com/novacode/worktree/AgentWorktree.java` | 高层 create（fast-resume）/remove/buildNotice/readHead |
| 新建 | `src/main/java/com/novacode/worktree/PostCreationSetup.java` | 创建后环境初始化四步 |
| 新建 | `src/main/java/com/novacode/worktree/StaleCleanup.java` | 三层过滤后台清理 |
| 新建 | `src/main/java/com/novacode/worktree/WorktreeService.java` | 子 Agent enter/exit 门面 + 临时名 |
| 新建 | `src/main/java/com/novacode/worktree/WorktreeSession.java` | 会话记录 |
| 新建 | `src/main/java/com/novacode/worktree/WorktreeSessionStore.java` | 会话 JSON 持久化 |
| 新建 | `src/main/java/com/novacode/subagent/Isolation.java` | 隔离模式枚举 |
| 修改 | `src/main/java/com/novacode/subagent/SubAgentSpec.java` | 新增 isolation 组件 + 便捷构造器 |
| 修改 | `src/main/java/com/novacode/subagent/AgentLoader.java` | 解析 frontmatter `isolation` |
| 修改 | `src/main/java/com/novacode/tool/impl/ReadFileTool.java` | cwd 字段 + 相对路径解析 |
| 修改 | `src/main/java/com/novacode/tool/impl/WriteFileTool.java` | 同上 |
| 修改 | `src/main/java/com/novacode/tool/impl/EditFileTool.java` | 同上 |
| 修改 | `src/main/java/com/novacode/tool/impl/GlobTool.java` | 同上 |
| 修改 | `src/main/java/com/novacode/tool/impl/GrepTool.java` | 同上 |
| 修改 | `src/main/java/com/novacode/tool/impl/BashTool.java` | cwd 字段 + 进程目录 |
| 修改 | `src/main/java/com/novacode/subagent/ToolFilter.java` | cwd 重载 + 注入 |
| 修改 | `src/main/java/com/novacode/permission/PermissionEngine.java` | withSandbox |
| 修改 | `src/main/java/com/novacode/subagent/SubAgentRunner.java` | buildAgent 重载（sandboxRoot） |
| 修改 | `src/main/java/com/novacode/subagent/AgentTool.java` | worktree 编排 + setWorktreeService |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | 构造并注入 WorktreeService |
| 新建 | `target/smoke/Smoke14.java` | 冒烟测试 |

## T1: SlugValidator

**文件：** `worktree/SlugValidator.java`
**依赖：** 无
**步骤：**
1. 定义 `MAX_LENGTH=64`、`VALID_SEGMENT`、`EPHEMERAL` 等常量。
2. 实现 `validate(String)`：trim 后非空；拒绝前导 `/`；按 `/` 分段，每段非空、非 `.`/`..`、匹配
   `VALID_SEGMENT`；整体长度 ≤64。通过返回 null，否则返回可读错误。
3. 实现 `flatten`（`/`→`+`）与 `branchName`（`worktree-` 前缀）。

**验证：** 编译通过；Smoke14 中 `validate("../../etc")`、`validate("/abs")`、`validate("a//b")`、
`validate(".")`、`validate("..")`、超长名、`validate("a*b")` 均非 null；`validate("a/b/c")`、
`validate("my.worktree-1")` 为 null；`branchName("a/b")=="worktree-a+b"`。

## T2: WorktreeManager

**文件：** `worktree/WorktreeManager.java`
**依赖：** T1
**步骤：**
1. 定义 `WorktreeInfo` record 与 `runGit(dir, args...)` 辅助（合并 stdout/stderr，返回退出码 + 输出）。
2. 实现 `detectGitRoot`、`create(branch,target)`、`remove(target)`、`list()`、`currentBranch`、`hasUpstream`。
3. `remove` = `git worktree remove --force <dir>` 后 `git branch -D <branch>`（分支来自 worktree 名或 HEAD）。

**验证：** 在临时 git 仓库上：`create` 后目录存在且 HEAD 正确；`list()` 含新 worktree；`remove` 后目录消失。

## T3: WorktreeChanges

**文件：** `worktree/WorktreeChanges.java`
**依赖：** 无（直接用 git CLI）
**步骤：**
1. `hasLocalChanges(dir)`：`git -C dir status --porcelain` 非空为 true；异常→true。
2. `hasNewCommits(dir, base)`：`git -C dir rev-list --count base..HEAD` >0；异常→true。
3. `hasChanges(dir, base)` = 二者或。

**验证：** 空 worktree → false；写一个未提交文件 → true；提交一个 → `hasNewCommits` true。

## T4: AgentWorktree

**文件：** `worktree/AgentWorktree.java`
**依赖：** T1、T2
**步骤：**
1. 定义 `Result` record。
2. `create(gitRoot,name,parentDir)`：校验 name；`target=parentDir.resolve(name)`；若目录已存在→fast-resume
   （`readHead` + bump mtime，不跑 git）；否则 `WorktreeManager.create(branchName,target)`，再 `readHead`。
3. `readHead(dir)`：读 `dir/.git` 指针得 gitdir；读 `gitdir/HEAD` 得 ref 或 hash；ref 时读 `gitdir/<ref>`。
4. `remove(Result)` 委托 `WorktreeManager.remove`。
5. `buildNotice(Result, originalCwd)`：产出 `<worktree-notice>` 文本（cwd、分支、head、originalCwd）。

**验证：** 首次 create 建目录；二次 create 同路径走 fast-resume（head 一致）；`readHead` 与 `git rev-parse HEAD` 一致。

## T5: PostCreationSetup

**文件：** `worktree/PostCreationSetup.java`
**依赖：** T2
**步骤：**
1. `setup(worktree, gitRoot)` 四步，逐步 try/catch 容错。
2. 复制 `.novacode/settings.local.json`；`.husky` 存在则 `git -C worktree config core.hooksPath .husky`；
   软链清单 `.novacode/worktree-symlinks.txt` 逐行建软链（降级 junction）；`.worktreeinclude` 逐行复制。

**验证：** 配好清单后 create，清单文件被复制、软链目录可见；无清单时无副作用、不抛异常。

## T6: StaleCleanup

**文件：** `worktree/StaleCleanup.java`
**依赖：** T2、T3
**步骤：**
1. `EPHEMERAL` 模式 + `isEphemeral(dir)`。
2. `cleanup(parentDir,maxAgeMs)`：遍历子目录，三层过滤（临时名→超时→`!hasLocalChanges`），
   通过者 `WorktreeManager.remove`，返回被删列表。
3. 可选 `startCleanupLoop`（守护线程，周期调用 cleanup）。

**验证：** 造一个过期空临时目录 → 被删；一个带未提交改动的 → 保留；一个未到期的 → 保留。

## T7: WorktreeService / WorktreeSession / WorktreeSessionStore

**文件：** `worktree/WorktreeService.java`、`WorktreeSession.java`、`WorktreeSessionStore.java`
**依赖：** T1、T4、T5
**步骤：**
1. `WorktreeSession` record + `WorktreeSessionStore`（JSON 读写 save/load/clear）。
2. `WorktreeService(gitRoot)`；`enter(name)` = validate→create→post-setup→notice，返回 `Enter`；
   `exit(Enter)` = `WorktreeChanges.hasChanges(cwd,head)` ? 保留 false : 删除 true；
   `newEphemeralName()` 生成 `agent-<8hex>`。

**验证：** `enter("x")` 建目录；`exit` 空改动删除、有改动保留；`newEphemeralName` 匹配 `^agent-[0-9a-f]{8}$`。

## T8: Isolation 枚举 + SubAgentSpec 扩展 + AgentLoader 解析

**文件：** `subagent/Isolation.java`、`subagent/SubAgentSpec.java`、`subagent/AgentLoader.java`
**依赖：** T7
**步骤：**
1. 定义 `Isolation { NONE, WORKTREE; static parse }`。
2. `SubAgentSpec` 新增 `isolation` 组件 + 8 参便捷构造（NONE）。
3. `AgentLoader.parseAgentFile` 解析 frontmatter `isolation`（`Isolation.parse`）。

**验证：** 编译通过；Smoke14 解析含 `isolation: worktree` 的角色文件得到 `WORKTREE`；旧构造调用仍编译。

## T9: 工具 cwd 注入

**文件：** `tool/impl/{ReadFileTool,WriteFileTool,EditFileTool,GlobTool,GrepTool,BashTool}.java`
**依赖：** 无
**步骤：**
1. 每类加 `Path cwd = Path.of(System.getProperty("user.dir"))` + `setCwd(Path)`。
2. 文件工具加 `resolvePath(String)`（相对→cwd.resolve→normalize）；Glob/Grep 的 path、Bash 的 directory 改用它。
3. FileStateCache 记录仍用 `toAbsolutePath()`（现因已解析到 cwd 而正确）。

**验证：** `setCwd(tmp)` 后 ReadFile 读相对路径命中 tmp 下文件；Bash `cd`/`pwd` 输出 tmp；Glob 相对路径列出 tmp。

## T10: ToolFilter cwd 重载

**文件：** `subagent/ToolFilter.java`
**依赖：** T9
**步骤：**
1. 新增 4 参 `filterForAgent(source,spec,async,cwd)`，原两个重载委托。
2. `freshBuiltin` 重建后 `setCwd(cwd)`（null 跳过）。

**验证：** `filterForAgent(parent,spec,false,cwd)` 产出的 ReadFileTool/BashTool 的 cwd 已设为 cwd。

## T11: PermissionEngine.withSandbox

**文件：** `permission/PermissionEngine.java`
**依赖：** 无
**步骤：** 新增 `withSandbox(PathSandbox)`，共享其余字段返回新实例。

**验证：** 编译通过；Smoke14 断言沙箱根替换后越界路径被拒。

## T12: SubAgentRunner.buildAgent 重载

**文件：** `subagent/SubAgentRunner.java`
**依赖：** T11
**步骤：** 新增 5 参 `buildAgent(..., Path sandboxRoot)`，非 null 时 `forSubAgent(mode).withSandbox(new PathSandbox(root))`。

**验证：** 编译通过；Smoke14 用 worktree 根构造后，子 Agent 权限引擎沙箱根正确。

## T13: AgentTool worktree 编排

**文件：** `subagent/AgentTool.java`
**依赖：** T7、T10、T12
**步骤：**
1. 加 `WorktreeService worktreeService` + `setWorktreeService`。
2. `runSync`/`runAsync`：`isolation==WORKTREE && worktreeService!=null` 时 enter（含 notice），
   cwd 传入 filterForAgent 与 buildAgent，notice 追加 system prompt，finally 里 exit。
3. 后台路径在 worker 内 enter/exit。

**验证：** Smoke14 用假 client 派发一个 `isolation: worktree` 角色，断言 worktree 目录创建/删除。

## T14: ChatModel 接线

**文件：** `ui/ChatModel.java`
**依赖：** T13
**步骤：** 构造 `WorktreeService(projectRoot)` 并 `agentTool.setWorktreeService(...)`。

**验证：** 编译通过 + 打包成功。

## T15: Smoke14 + 打包

**文件：** `target/smoke/Smoke14.java`
**依赖：** T1–T14
**步骤：** 编写覆盖 AC1–AC7 的冒烟测试（临时 git 仓库 + 假 LLM client），编译运行，`mvn -q package`。

**验证：** PASS 全绿 FAIL=0；生成 `target/novacode-1.0.0.jar` 与 `-shaded.jar`。

## 执行顺序

```
T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14 → T15
```
（T1/T9/T11 可并行；T7 依赖 T1/T4/T5；T13 依赖 T7/T10/T12。）
