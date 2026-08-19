# 第 15 章 长期团队 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/novacode/teams/MailMessage.java` | 消息记录 + 摘要派生 |
| 新建 | `src/main/java/com/novacode/teams/TeamFile.java` | 团队配置磁盘形态 + sanitizeTeamName |
| 新建 | `src/main/java/com/novacode/teams/FileMailBox.java` | 邮箱文件 + 锁文件并发协议 |
| 新建 | `src/main/java/com/novacode/teams/SharedTaskStore.java` | 共享任务板 tasks.json |
| 新建 | `src/main/java/com/novacode/teams/TeamProtocol.java` | 结构化消息常量/工厂 |
| 新建 | `src/main/java/com/novacode/teams/AgentNameRegistry.java` | 全局名字→投递标识 |
| 新建 | `src/main/java/com/novacode/teams/TeamManager.java` | Team/Member/后端检测/生命周期/持久化 |
| 新建 | `src/main/java/com/novacode/teams/TeammateProgress.java` | 队员进度快照 |
| 新建 | `src/main/java/com/novacode/teams/TeammateRunner.java` | 进程内队员循环 + 静态助手 |
| 新建 | `src/main/java/com/novacode/teams/SpawnDispatcher.java` | 按后端派发队员 |
| 新建 | `src/main/java/com/novacode/teams/TeamTools.java` | SendMessage / TeamCreate / TeamDelete |
| 新建 | `src/main/java/com/novacode/teams/TeamTaskTools.java` | TaskCreate / Get / List / Update |
| 新建 | `src/main/java/com/novacode/teams/TaskStopTool.java` | 中止队员 |
| 新建 | `src/main/java/com/novacode/teams/Coordinator.java` | 工具收窄 + 双锁判定 |
| 新建 | `src/main/java/com/novacode/teams/CoordinatorPrompt.java` | 调度指引（全文/精简） |
| 新建 | `src/main/java/com/novacode/teams/TeamMerge.java` | 分支合并 + 冲突回滚 |
| 修改 | `src/main/java/com/novacode/subagent/ToolFilter.java` | 协作工具隐形 + filterForTeammate |
| 修改 | `src/main/java/com/novacode/agent/Agent.java` | setToolNameFilter + setCoordinatorActiveFn + 指引注入 |
| 修改 | `src/main/java/com/novacode/subagent/AgentTool.java` | team_name/name/plan_mode_required → runAsTeammate |
| 修改 | `src/main/java/com/novacode/subagent/SubAgentRunner.java` | buildTeammateAgent 助手 |
| 修改 | `src/main/java/com/novacode/config/ConfigLoader.java` | enable_coordinator_mode 字段 + loadConfig |
| 修改 | `src/main/java/com/novacode/App.java` | coordinator 标志 + --teammate 入口 |
| 修改 | `src/main/java/com/novacode/ui/ChatModel.java` | TeamManager + 团队工具 + 通知排空 + coordinator 装配 |
| 新建 | `target/smoke/Smoke15.java` | 行为验收（对照 checklist） |

## T1: 基础消息记录 MailMessage

**文件：** `src/main/java/com/novacode/teams/MailMessage.java`
**依赖：** 无
**步骤：**
1. 定义 `record MailMessage(String from, String text, String timestamp, boolean read, String summary, String type, String requestId, Boolean approve)`。
2. 写紧凑构造器 `MailMessage(String from, String text)`：`from` 非空校验；时间戳取 `Instant.now().toString()`（ISO-8601）；`read=false`；`summary` 自动派生 = 正文去空白后截断 ≤120 字符（超长补 `…`）；`type=TEXT`；`requestId=null`；`approve=null`。
3. 写 `withRead(boolean)`：复制并替换 `read` 字段，其余原样。
4. 写静态 `summaryOf(String text)` 供构造器与外部复用。
5. 用 Map 序列化助手 `toMap(MailMessage)` / `fromMap(Map)`（参照 `WorktreeSessionStore` 模式，空字段写 null）。

**验证：** `mvn -q compile` 通过；Smoke15「msg: auto timestamp」「msg: default unread」「msg: summary truncated」「msg: withRead」覆盖。

## T2: 团队配置磁盘形态 TeamFile

**文件：** `src/main/java/com/novacode/teams/TeamFile.java`
**依赖：** T1
**步骤：**
1. `public static String sanitizeTeamName(String name)`：null 返回 `"untitled"`；`replaceAll("[^a-zA-Z0-9]", "-")` 再 `toLowerCase()`，结果空 → `"untitled"`。
2. 定义成员元数据 record `MemberInfo(String name, String agentType, String model, String worktreePath, boolean needsApproval, long joinedAt)`。
3. `write(Path configPath, String name, String mode, String leadAgentId, String description, long createdAt, List<MemberInfo> members)`：写 JSON（Map 结构，字段用 snake_case：`name/mode/lead_agent_id/description/created_at/members[]`）。
4. `static TeamData read(Path configPath)`：返回 record `TeamData(String name, String mode, String leadAgentId, String description, long createdAt, List<MemberInfo> members)`；文件缺失/损坏返回 null。

**验证：** `mvn -q compile` 通过；Smoke15「team: round-trip」覆盖。

## T3: 邮箱 FileMailBox + 锁协议

**文件：** `src/main/java/com/novacode/teams/FileMailBox.java`
**依赖：** T1
**步骤：**
1. 字段：`Path inboxesDir`、`Map<String, ReentrantLock> inProcessLocks`（ConcurrentHashMap）。
2. 常量：`LOCK_ACQUIRE_TIMEOUT_MS=5000`、`STALE_LOCK_AGE_SECONDS=10`、`BACKOFF_MIN_MS=5`、`BACKOFF_MAX_MS=80`。
3. `send(String recipient, MailMessage msg)`：以 `inProcessLocks.computeIfAbsent` 拿内存锁 → 文件锁 `acquireFileLock(Path mailboxFile)` → 读现有消息列表 → 追加 → 写回 → 释放。
4. `acquireFileLock(Path target)`：锁文件 = `<mailbox>.lock`；`Files.createFile` 原子抢锁；`FileAlreadyExists` → 读 mtime，超过 `STALE_LOCK_AGE_SECONDS` 删锁文件重试（视为陈旧接管）；否则指数退避（5→80ms 带 Random 抖动）重试；总时长超 `LOCK_ACQUIRE_TIMEOUT_MS` 抛 `IllegalStateException`。
5. `releaseFileLock(Path target)`：try-with-resources 风格，删除锁文件。
6. `readUnread(String recipient)`：读邮箱文件，返回未读消息列表，不标记已读。
7. `markAllRead(String recipient)`：把全部消息 `withRead(true)` 写回。
8. `list(String recipient)`：返回全部消息。
9. `mailboxFile(String recipient)`：`inboxesDir/<recipient>.json`；`createDirectories` 于首次写时。
10. 空邮箱文件 / 损坏 → 返回空列表（容错）。

**验证：** `mvn -q compile` 通过；Smoke15「mail: round-trip」「mail: unread default」「mail: markAllRead」「mail: concurrent 50 threads no loss」「mail: stale lock takeover」覆盖。

## T4: 共享任务板 SharedTaskStore

**文件：** `src/main/java/com/novacode/teams/SharedTaskStore.java`
**依赖：** T1
**步骤：**
1. 定义 record `SharedTask(String id, String title, String description, String status, String assignee, List<String> blocks, List<String> blockedBy, String createdBy)`；紧凑构造器把 `blocks`/`blockedBy` 归一化为非 null（去重保留序）。
2. 字段：`Path tasksFile`、`ObjectMapper JSON`、内存 `List<SharedTask> tasks`、`int nextId`。
3. `load()`：磁盘重载，不存在 → `initEmpty()`（空列表 + nextId=1）；损坏 → 重置为空（容错）。每次公开读操作前调用保证跨进程最新。
4. `initEmpty()`：置空并写盘。
5. `create(title, desc, assignee, blocks, blockedBy, createdBy)`：`id = "task-" + (nextId++)`，status=pending，写盘，返回新任务。
6. `get(id)`：按 id 返回或 null。
7. `listTasks(String status, String assignee)`：status/assignee 为 null 或空则不过滤。
8. `update(id, status, assignee, desc, addBlocks, addBlockedBy)`：按 id 更新；status 为空跳过；addBlocks/addBlockedBy 追加去重；返回更新后任务或 null。
9. 状态校验静态方法 `isValidStatus(String)`：`pending/in_progress/completed/blocked`。
10. 落盘格式：`{ "next_id": N, "tasks": [ {id,title,description,status,assignee,blocks,blocked_by,created_by} ] }`。

**验证：** `mvn -q compile` 通过；Smoke15「task: crud」「task: deps append dedup」「task: filter」「task: persists across stores」覆盖。

## T5: 协议常量 + 名称注册表

**文件：** `src/main/java/com/novacode/teams/TeamProtocol.java`、`src/main/java/com/novacode/teams/AgentNameRegistry.java`
**依赖：** T1
**步骤：**
1. TeamProtocol 常量：`TEXT`、`SHUTDOWN_REQUEST`、`SHUTDOWN_RESPONSE`、`PLAN_APPROVAL_REQUEST`、`PLAN_APPROVAL_RESPONSE`。
2. `newRequestId()`：`"req-"` + 16 个十六进制字符（`SecureRandom`）。
3. `shutdownRequest(from)`、`shutdownResponse(from, requestId)`、`planApprovalRequest(from, requestId, text)`、`planApprovalResponse(from, requestId, approve)` —— 各返回构造好的 MailMessage。
4. `isShutdownRequest(MailMessage)`：type==SHUTDOWN_REQUEST 或正文以 `[shutdown]` 开头。
5. `approved(MailMessage)`：`Boolean.TRUE.equals(msg.approve())`（fail-closed）。
6. AgentNameRegistry：单例 `getInstance()`；`register(String name, String address)`、`resolve(String name)`、`unregister(String name)`；线程安全（ConcurrentHashMap）。

**验证：** `mvn -q compile` 通过；Smoke15「proto: request id format」「proto: shutdown detection」「proto: approved fail-closed」「registry: resolve/overwrite」覆盖。

## T6: TeamManager（Team/Member/生命周期/持久化）

**文件：** `src/main/java/com/novacode/teams/TeamManager.java`
**依赖：** T2、T3、T4、T5
**步骤：**
1. 枚举 `public enum TeamMode { IN_PROCESS, TMUX, ITERM }`（顶层或嵌套于 TeamManager）。
2. `static Path teamsBaseDir()`：`~/.novacode/teams`（`System.getProperty("user.home")`）。
3. `static TeamMode detectBackendFromEnv(String tmux, String itermSessionId)`：tmux 非空 → TMUX；否则 itermSessionId 非空 → ITERM；否则 IN_PROCESS。
4. `static TeamMode detectBackend()`：Windows（`os.name` 含 "windows"）恒 IN_PROCESS；否则读 `System.getenv` 走 detectBackendFromEnv。
5. 内部类 `Team`：字段 `name`、`mode`、`LinkedHashMap<String, Member> members`、`FileMailBox mailBox`、`String leadAgentId`、`String description`、`long createdAt`。方法：
   - `getMember(name)` / `hasMember(name)` / `memberNames()`（有序 List）。
   - `addMember(Member m)`。
   - `setMemberMeta(name, agentType, model, worktreePath, needsApproval)`：补元信息 + persist。
   - `sendMessage(recipient, MailMessage)`：转发 mailBox.send。
   - `stopAll()`：对所有 active 成员 `Thread.interrupt()`。
   - `snapshot()`：返回 TeamFile 需要的成员元数据快照（List\<MemberInfo\>）。
6. 内部类 `Member`：字段 `name`、`volatile Agent agent`、`volatile List<ChatMessage> history`、`volatile boolean active`、`volatile Thread thread`、`volatile TeammateProgress progress`、`agentType`、`model`、`worktreePath`、`long joinedAt`、`boolean needsApproval`。构造器 `Member(String name)` 默认 joinedAt=now、active=false。
7. `Team createTeam(String name, TeamMode mode, String leadAgentId, String description)`：目录 = `teamsBaseDir()/sanitizeTeamName(name)`；建 mailBox + taskStore；register 进 `teams` map；persist；返回。
8. `Team getTeam(String name)`：内存命中直接返回；否则磁盘重建（`TeamFile.read` → 新 Team + `setMemberMeta` 逐个 + `restoreTaskStore`）。没有则返回 null。
9. `Team getOrCreateTeam(name, mode, leadId, desc)`：无则建。
10. `boolean deleteTeam(String name)`：stopAll + unregister + 递归删目录；不存在返回 false。
11. `List<String> listTeams()`：`teams` map 的 key 列表。
12. `void closeAll()`：遍历 stopAll。
13. `String senderTeamName(String senderName, String defaultTeamName)`：`"lead"` 或未知名字 → 返回传入的 defaultTeamName；否则查 team 成员归属（用于 SendMessage 解析）。
14. `SharedTaskStore getTaskStore(String teamName)`：每个 Team 内嵌 taskStore，暴露 `Team.taskStore()` 即可。

**验证：** `mvn -q compile` 通过；Smoke15「team: create layout」「team: disk rebuild」「team: delete removes dir」「team: detectBackend env priority」「backend: windows forced in-process」覆盖。

## T7: 队员进度 + 队员循环

**文件：** `src/main/java/com/novacode/teams/TeammateProgress.java`、`src/main/java/com/novacode/teams/TeammateRunner.java`
**依赖：** T6
**步骤：**
1. TeammateProgress：record `TeammateProgress(String status, int toolCount, long lastActiveAt)`；静态 `initial()`、`withStatus`、`touch`（toolCount+1、刷新 lastActiveAt）。
2. TeammateRunner：
   - 常量 `IDLE_POLL_MS=500`、`SHUTDOWN_PREFIX="[shutdown]"`、`LEAD_NAME="lead"`。
   - `runInProcessTeammate(TeamManager teamManager, TeamManager.Team team, TeamManager.Member member, String systemPrompt, boolean needsApproval, String leadAgentId)`：核心循环——
     1. `member.active=true`，挂 initial progress；可选初始 prompt 由 spawner 预注入 history。
     2. `injectPendingMessages(member, team)`：把未读邮箱折叠成 `<system-reminder>` user 消息追加进 history（先 markAllRead）。
     3. `agent.run(history, systemPrompt, planMode)` → 内部 `drain` 到 LoopComplete，期间更新 progress（toolCount、status）。
     4. needsApproval 且本计划尚未获批：发 `planApprovalRequest`（正文=本计划全文），status=awaiting plan approval，进入 `awaitPlanApproval`：轮询邮箱找匹配 requestId 的 PLAN_APPROVAL_RESPONSE，approve=true → 以 planMode=false 重跑；approve=false → 以 planMode=true 重跑修订计划再发请求。
     5. 非审批成员一轮完成：向 lead 发 idle 通知 `[idle] <name> (reason: available)`（`TeamProtocol` 普通 text）。
     6. 空闲轮询邮箱（IDLE_POLL_MS）：收到 `shutdownRequest` → 回 `shutdownResponse(approve=true)` 后退出循环；收到其它消息 → 作为用户消息续跑（历史继续累积，磁盘可恢复上下文）。
     7. finally：`member.active=false`，status=completed。
   - 静态助手：
     - `buildTeammateAddendum(String name, String leadAgentId, String teamName)`：系统提示附加段，说明自己是团队成员、用 SendMessage 汇报、共享任务板用法、用 Bash 在 worktree 干活、结束前发 idle。
     - `injectPendingMessages(TeamManager.Team team, Member member)`。
     - `createIdleNotification(String name, String reason)`。
     - `drainLeadMailbox(TeamManager tm, String teamName)`：把 lead 收件箱未读消息格式化成一串 `<team-notification>` user 消息（带 from/摘要/正文），markAllRead 后返回列表（ChatModel 注入）。
     - `buildTeammateRegistry(ToolRegistry source, TeamManager tm, String teamName, String memberName)`：`filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE, false)` 基础注册表 + 注册 SendMessage + TaskCreate/TaskGet/TaskList/TaskUpdate 四件套（TeamTaskTools/TeamTools 的共享实例）。
     - `resolveTeamForMember(TeamManager tm, String senderName, String defaultTeamName)`。

**验证：** `mvn -q compile` 通过；Smoke15「teammate: buildAddendum」「teammate: injectPending folds unread」「teammate: idle notification text」「teammate: drainLeadMailbox formats + marks read」覆盖。

## T8: SpawnDispatcher

**文件：** `src/main/java/com/novacode/teams/SpawnDispatcher.java`
**依赖：** T7
**步骤：**
1. record `SpawnConfig(String teamName, String memberName, String prompt, String systemPrompt, String model, String worktreePath, PermissionMode mode, int maxTurns, boolean needsApproval, String leadAgentId)`。
2. `spawnTeammate(SpawnDispatcher.SpawnConfig cfg)` 返回 `String`（状态消息）：
   - IN_PROCESS：`TeamManager.createTeam` 若缺 → addMember → build agent（`SubAgentRunner.buildTeammateAgent`）→ 预注入 prompt → `Thread.startVirtualThread(runInProcessTeammate)`。返回 `"teammate <name> started in-process"`。
   - TMUX / ITERM：先把初始 prompt 写进队员邮箱（以 lead 名义）→ `buildTeammateCLI` → 用 `ProcessBuilder` 启动 tmux `new-window` / osascript iTerm；`ProcessBuilder` 启动抛异常或返回码非 0 → 抛 `IllegalStateException`（不静默降级）。返回 pane/window 提示。
3. `buildTeammateCLI(String teamName, String memberName, String workdir)`：`<currentJar> --teammate --team-name <t> --agent-name <n>`（含 shellQuote）。
4. `static String shellQuote(String s)`：包单引号、转义单引号（POSIX 与 cmd 都安全的保守实现）。
5. `static String uniqueMemberName(TeamManager.Team team, String base)`：已存在则加 `-2`、`-3` 去重。
6. Windows 下 TMUX/ITERM 分支：恒走 IN_PROCESS（由 detectBackend 保证，无需额外判断）。

**验证：** `mvn -q compile` 通过；Smoke15「spawn: cli string」「spawn: shellQuote」「spawn: unique member name」「spawn: tmux mode fails loudly (env forced)」覆盖。

## T9: 协作工具三件套

**文件：** `src/main/java/com/novacode/teams/TeamTools.java`、`src/main/java/com/novacode/teams/TeamTaskTools.java`、`src/main/java/com/novacode/teams/TaskStopTool.java`
**依赖：** T5、T6、T8
**步骤：**
1. TeamTools：
   - `SendMessageTool(TeamManager teamManager, String senderName)`：schema `to`(string, 必需)、`content`(string, 必需)、`type`(string, 可选)、`request_id`(string, 可选)、`approve`(boolean, 可选)。execute：
     - 解析 sender 团队：senderName 为 "lead" 或不在任何 team → 用 Tool 构造时的 defaultTeamName（构造注入）；否则从成员表反查。
     - `to=="*"` → 广播同队其余成员（不含 lead）；`to=="lead"` → 发给 leadAgentId 邮箱；否则 `AgentNameRegistry.resolve(to)` → 写目标成员邮箱。
     - 结构化类型（shutdown_request 等）直接用传入 type 与 requestId/approve 构造；默认 TEXT。
     - 返回 `"sent to <n> recipients"`。
   - `TeamCreateTool(TeamManager teamManager, String leadAgentId)`：`name`(必需)、`description`(可选)。execute：同名已存在 → `name-2`/`name-3` 去重（uniqueTeamName）；`TeamManager.detectBackend()` 选模式；返回 `"created team <name> (mode)"`。
   - `TeamDeleteTool(TeamManager teamManager)`：`name`(必需)。不存在返回错误；存在 → deleteTeam + 返回成功。
   - `uniqueTeamName(TeamManager tm, String base)` 静态助手。
2. TeamTaskTools：
   - `TaskCreateTool(TeamManager tm, String teamName)`：`title`(必需)、`description`/`assignee`/`blocks`/`blocked_by`/`created_by`(可选)。调 `taskStore.create`，返回 `"created <id>"`。
   - `TaskGetTool`：`id`(必需)，返回任务 JSON 文本或错误。
   - `TaskListTool`：`status`/`assignee`(可选)，返回格式化列表。
   - `TaskUpdateTool`：`id`(必需)、`status`/`assignee`/`description`/`add_blocks`/`add_blocked_by`(可选)；status 非法 → 错误。
   - 构造注入 teamName；`TeamManager.getTaskStore(teamName)`。
3. TaskStopTool：`agent_id`(必需)。跨队查 member：不在跑 → 返回 "not running"（成功）；在跑 → `Thread.interrupt()` → "stopping"。fail-safe（任何异常转错误结果）。

**验证：** `mvn -q compile` 通过；Smoke15「tools: sendMessage text/broadcast/lead」「tools: teamCreate dedup + detectBackend」「tools: task crud through tools」「tools: taskStop states」覆盖。

## T10: Coordinator + 指引 + TeamMerge

**文件：** `src/main/java/com/novacode/teams/Coordinator.java`、`src/main/java/com/novacode/teams/CoordinatorPrompt.java`、`src/main/java/com/novacode/teams/TeamMerge.java`
**依赖：** T6、T7
**步骤：**
1. Coordinator：
   - `public static final Set<String> ALLOWED_TOOLS = Set.of("Agent", "SendMessage", "TaskStop", "TeamDelete", "TeamMerge", "ReadFile", "Glob", "Grep", "Bash")`。
   - `static boolean isCoordinatorTool(String name)`。
   - `static boolean envEnabled()`：`NOVACODE_COORDINATOR` 环境变量 ∈ {"1","true"}（大小写不敏感、去空白）。
   - `static boolean active(boolean configEnabled)`：`configEnabled && envEnabled()`（双锁）。
2. CoordinatorPrompt：
   - `static String fullPrompt()`：8KB 级调度指引（拆分用户目标→建任务→派生队员→监控→git 合并/回滚→汇报），含可用工具清单与禁止项（不自己写文件）。
   - `static String briefPrompt(int iter)`：每 5 轮含关键约束的极简复述；其余轮一行提示。
   - `static String buildReminder(int iter)`：iter==1 → full；iter%5==0 → full；else brief。
3. TeamMerge：
   - record `MergeResult(boolean ok, List<String> conflicts, boolean rolledBack, String message)`。
   - `static MergeResult merge(Path gitRoot, String branch)`：`git merge <branch>`（经 `WorktreeManager.runGit`）；ok → 成功结果；失败 → `git status --porcelain` 抓冲突文件（含 UU/AA/DD 行），`git merge --abort`，rolledBack=true，返回。
   - `static List<String> conflictFiles(Path gitRoot)`：解析 `--porcelain` 状态码首位为 U、或 `AA/DD` 的行。

**验证：** `mvn -q compile` 通过；Smoke15「coord: allowed/denied sets」「coord: env gate both locks」「merge: fast-forward」「merge: conflict rollback reports files」覆盖。

## T11: ToolFilter 协作工具隐形 + 队员过滤器

**文件：** `src/main/java/com/novacode/subagent/ToolFilter.java`
**依赖：** T9
**步骤：**
1. `ALWAYS_DISALLOWED` 追加 `"SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "TeamCreate", "TeamDelete", "TaskStop"`（普通子 Agent 与主入口主注册表不受影响——这些工具只在 ChatModel 显式注册给 Lead；普通子 Agent 过滤路径天然拿不到）。
2. 新增 `public static ToolRegistry filterForTeammate(ToolRegistry source, Path cwd)`：`filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE, false, cwd)`（协作工具已被挡掉），调用方再显式注册 SendMessage + Task* 四件套。

**验证：** `mvn -q compile` 通过；Smoke15「filter: collab hidden from normal subagent」「filter: teammate base excludes collab」覆盖。

## T12: Agent 工具名过滤器 + coordinator 指引注入

**文件：** `src/main/java/com/novacode/agent/Agent.java`
**依赖：** T10、T11
**步骤：**
1. 新增字段 `private volatile Predicate<String> toolNameFilter;` 与 `setToolNameFilter(Predicate<String>)`。
2. `computeSchemas(boolean planMode)`：在现有逻辑得出 schemas 后，若 `toolNameFilter != null` 再过滤（`name` 不匹配的排除）。因 `client.setTools` 每轮重算（L186），激活即时生效。
3. 新增字段 `private volatile Supplier<Boolean> coordinatorActiveFn;` 与 `setCoordinatorActiveFn(Supplier<Boolean>)`。
4. 每轮循环开头（L186 附近）：`Supplier<Boolean> cf = coordinatorActiveFn; if (cf != null && Boolean.TRUE.equals(cf.get()))` → 把 `CoordinatorPrompt.buildReminder(iter)` 作为 `<system-reminder>` user 消息注入本轮（planReminder 同款：history.add → streamOnce 后 finally remove）。
5. 导入 `java.util.function.Predicate`、`java.util.function.Supplier`、`com.novacode.teams.CoordinatorPrompt`。

**验证：** `mvn -q compile` 通过；Smoke15「coord: filter hides write tools」「coord: reminder injected when active」覆盖（agent 需真实 stream 或走 fakeClient 观察 history）。

## T13: AgentTool 派队员 runAsTeammate

**文件：** `src/main/java/com/novacode/subagent/AgentTool.java`
**依赖：** T8、T12
**步骤：**
1. 注入 `private TeamManager teamManager;` + `setTeamManager(TeamManager)`。
2. schema() 追加参数：`team_name`(string)、`name`(string, 可选——队员名，缺省由 description 派生)、`plan_mode_required`(boolean, 可选——队员是否需审批，缺省 false)。
3. description() 追加说明：`team_name` 非空 → 作为团队成员运行（长期驻留、共享任务板 + 邮箱协作、干完空闲待命）；缺省走原有 subagent 路径。
4. execute()：`String teamName = str(args, "team_name")`；非空且 `teamManager != null` → `return runAsTeammate(...)`。
5. `runAsTeammate`：
   - `Team team = teamManager.getOrCreateTeam(teamName, TeamManager.detectBackend(), "lead", "auto team")`。
   - memberName = 显式 name 或 `SpawnDispatcher.slugify(description)`；`SpawnDispatcher.uniqueMemberName(team, memberName)` 去重。
   - 建 Member（agentType=subagent_type、model=modelOverride、needsApproval=plan_mode_required）→ addMember。
   - `buildTeammateAddendum` → 拼 systemPrompt（含 worktree 通知，可选）。
   - `SpawnDispatcher.spawnTeammate(cfg)`。
   - `member.history` 预注入 `USER prompt`。
   - 返回 spawn 状态消息（含团队名/队员名）。
6. `setTeamManager` 与 `cloneWithQuerySource` 传递新字段。

**验证：** `mvn -q compile` 通过；Smoke15「agenttool: team_name routes to teammate」「agenttool: member dedup」「agenttool: addendum present」覆盖。

## T14: SubAgentRunner buildTeammateAgent

**文件：** `src/main/java/com/novacode/subagent/SubAgentRunner.java`
**依赖：** T13
**步骤：**
1. `public Agent buildTeammateAgent(ToolRegistry registry, LlmClient client, PermissionMode mode, int maxTurns, Path sandboxRoot)`：复用 `buildAgent(...)`（DEFAULT 权限 + 可选 worktree 沙箱），仅作命名助手（保留 forSubAgent 语义，planMode 由 runner 决定）。

**验证：** `mvn -q compile` 通过。

## T15: ConfigLoader 能力开关

**文件：** `src/main/java/com/novacode/config/ConfigLoader.java`
**依赖：** 无
**步骤：**
1. `ConfigFile` 追加字段 `public Boolean enable_coordinator_mode;`（SNAKE_CASE 命名策略已生效）。
2. `public static boolean coordinatorEnabled(Path configPath)`：文件不存在 → false；解析后 `Boolean.TRUE.equals(configFile.enable_coordinator_mode)`。容错：解析异常返回 false。

**验证：** `mvn -q compile` 通过；Smoke15「coord: config flag parsing」覆盖。

## T16: App --teammate 入口 + coordinator 标志

**文件：** `src/main/java/com/novacode/App.java`
**依赖：** T15、T8
**步骤：**
1. `run(args)` 解析：`--teammate` 单独入口 → `runTeammate(args)`；否则原有启动。
2. `runTeammate(String[] args)`：`--team-name <t>` / `--agent-name <n>`（缺省报错退出）；加载 config 建 ChatModel 后不启动 TUI，直接：
   - 从磁盘 `TeamManager.getTeam(t)` 重建团队（含邮箱与成员元信息）。
   - 以该成员的邮箱为收件箱，跑 `TeammateRunner.runInProcessTeammate(...)`（systemPrompt 来自 config）。
   - `closeAll()` 后退出。
3. 主入口 `run`：`boolean coord = ConfigLoader.coordinatorEnabled(configPath)` → 传给 `ChatModel(config, coord)`。
4. `ChatModel` 构造器新增重载 `ChatModel(ProviderConfig, boolean coordinatorEnabled)`；原构造器委托传 false（向后兼容，Smoke 等不受影响）。

**验证：** `mvn -q compile` 通过；Smoke15「app: teammate flag parse」覆盖。

## T17: ChatModel 装配

**文件：** `src/main/java/com/novacode/ui/ChatModel.java`
**依赖：** T9、T10、T11、T12、T13、T16
**步骤：**
1. 字段 `TeamManager teamManager`、`boolean coordinatorEnabled`；构造器签名改 `ChatModel(ProviderConfig config)` → 内部 `this(config, false)`；新构造器接受 coordinatorEnabled。
2. 构造器（第 13 章区块附近）：
   - `this.teamManager = new TeamManager();`
   - 注册 Lead 团队工具到 toolRegistry：`new TeamTools.SendMessageTool(teamManager, "lead")`、`new TeamTools.TeamCreateTool(teamManager, "lead")`、`new TeamTools.TeamDeleteTool(teamManager)`、`new TeamTaskTools.TaskCreateTool(teamManager, defaultTeamName)`（task 工具的 teamName 用哨兵——sendMessage/teamCreate 结果里返回真实 teamName 供工具重建；或让 Task* 工具从 SendMessage 解析的 team 推断，实现为按 team 动态 resolveTaskStore：工具持有 TeamManager + `resolveTeamName(args)`，本步简化：Task* 工具把 teamName 参数作为可选项，缺省取第一个团队）。
   - `new TaskStopTool(teamManager)`。
   - `agentTool.setTeamManager(teamManager)`。
   - coordinator 双锁：`boolean coordActive = Coordinator.active(coordinatorEnabled)`；active 时 `agent.setToolNameFilter(Coordinator::isCoordinatorTool)`、`agent.setCoordinatorActiveFn(() -> coordinatorEnabled && Coordinator.envEnabled())`。
3. LoopComplete 分支（L715）：`drainSubAgentNotifications()` 后追加 `drainLeadMailbox()`：`TeammateRunner.drainLeadMailbox(teamManager, ...)` 返回的通知 user 消息加入 `history`，并显示 `Msg("tool", ...)`。
4. `closeAll`：程序退出路径调用 `teamManager.closeAll()`（若存在，仅在 ChatModel 生命周期内注册的 ShutdownHook 或 quit 路径）。

**验证：** `mvn -q compile` 通过；Smoke15 通过 ChatModel 外部无法实例化（需真实 config/client），由 App 启动路径手工验证 + Smoke15 用 TeamManager 直接构造验证装配等价逻辑。

## T18: Smoke15 行为验收

**文件：** `target/smoke/Smoke15.java`
**依赖：** T1-T17
**步骤：**
1. 参照 Smoke14 模板（fakeClient/cfg/parentPerm/check 框架），import `com.novacode.teams.*`、`com.novacode.worktree.*`。
2. 覆盖 checklist.md 全部行为条目（见 T1-T10 标注的覆盖点），编译命令：
   ```
   export JAVA_HOME=/d/develop/jdk21
   /d/develop/jdk21/bin/javac -cp "target/classes;$DEP" -d target/smoke target/smoke/Smoke15.java
   java -cp "target/smoke;target/classes;$DEP" com.novacode.smoke.Smoke15
   ```
   `$DEP` 读 `target/smoke/cp.txt`。
3. 全部 PASS、FAIL=0，`exit(0)`。
4. `mvn -q -DskipTests package` 构建 jar（`--teammate` 入口依赖它的 `java -jar` 路径）。

**验证：** 终端输出 `==== PASS=N FAIL=0 ====`。

## 执行顺序

```
T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10
   ↘ T11 → T12 → T13 → T14
T15 → T16 → T17
T18（最后）
```
T1-T10 严格串行（后续依赖先前）；T11-T14 依赖 T9/T10 但彼此可并（按顺序执行即可）；T15-T16 独立可提前；T17 依赖全部；T18 收尾。
