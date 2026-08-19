# 第 15 章 长期团队 Plan

## 架构概览

新增 `com.novacode.teams` 包，沿参考实现（MewCode ch15）映射到 NovaCode 架构：

- **TeamManager**：内存团队注册表 + 磁盘持久化（`~/.novacode/teams/<slug>/`），持有
  `Team`（成员表 + 邮箱 + 共享任务库）与 `Member`（Agent 句柄 + 对话历史 + 运行态）。
- **FileMailBox / MailMessage**：每成员一个邮箱 JSON 文件 + 锁文件并发协议。
- **SharedTaskStore / SharedTask**：团队共享任务板（`tasks.json`）。
- **TeamProtocol / AgentNameRegistry**：结构化消息常量/构造器 + 全局名字→投递标识映射。
- **TeamTools / TeamTaskTools / TaskStopTool**：Lead 与队员的工具集。
- **TeammateRunner**：进程内队员常驻循环（空闲轮询、计划审批、空闲通知、关闭应答）。
- **SpawnDispatcher**：按 Team.mode 派生队员（进程内 / tmux / iTerm2）。
- **Coordinator / CoordinatorPrompt**：调度工具收窄 + 每轮注入的调度指引。
- **TeamMerge**：队员 worktree 分支合并 + 冲突回滚。

集成改动：`ToolFilter`（协作工具对普通子 Agent 隐形、队员工具集组装）、`AgentTool`
（`team_name` / `name` / `plan_mode_required` 参数 → 派生队员）、`SubAgentRunner`
（队员 Agent 构造助手）、`Agent`（coordinator 工具过滤 + 指引注入）、`ConfigLoader` /
`App` / `ChatModel`（coordinator 双锁 + 团队工具注册 + Lead 收件箱通知）。

## 核心数据结构

### TeamManager.Team
```java
public static class Team {
    final String name;
    final TeamMode mode;                    // IN_PROCESS | TMUX | ITERM
    final Map<String, Member> members = new LinkedHashMap<>();
    final FileMailBox mailBox;              // <teamDir>/inboxes
    String leadAgentId; String description; long createdAt;
    // getMember / hasMember / memberNames / addMember / setMemberMeta /
    // startMember / stopMember / stopAll / sendMessage / persist / snapshot
}
```

### TeamManager.Member
```java
public static class Member {
    public final String name;
    public volatile Agent agent;              // 进程内队员持有；窗格队员为 null
    public volatile List<ChatMessage> history;// 共享可变对话（磁盘可恢复的上下文）
    public volatile boolean active;
    public volatile Thread thread;            // 进程内队员线程
    public volatile TeammateProgress progress;
    public String agentType; public String model; public String worktreePath;
    public long joinedAt; public boolean needsApproval;
}
```

### FileMailBox.MailMessage
```java
public record MailMessage(String from, String text, String timestamp,
                          boolean read, String summary,
                          String type, String requestId, Boolean approve) {
    MailMessage(String from, String text);            // 自动时间戳 + 未读 + 摘要
    MailMessage withRead(boolean);                    // 复制并改已读
}
```
摘要自动派生（正文截断 ≤120 字符）。

### SharedTaskStore.SharedTask
```java
public record SharedTask(String id, String title, String description, String status,
                         String assignee, List<String> blocks, List<String> blockedBy,
                         String createdBy) { /* 列表字段归一化为非 null */ }
```
`tasks.json` = `{ next_id, tasks[] }`。状态枚举：`pending / in_progress / completed / blocked`。

### TeamProtocol
```java
public static final String TEXT = "text";
public static final String SHUTDOWN_REQUEST = "shutdown_request";
public static final String SHUTDOWN_RESPONSE = "shutdown_response";
public static final String PLAN_APPROVAL_REQUEST = "plan_approval_request";
public static final String PLAN_APPROVAL_RESPONSE = "plan_approval_response";
static String newRequestId();               // "req-" + 16 hex（跨进程不撞）
static boolean isShutdownRequest(MailMessage);
static boolean approved(MailMessage);       // 缺省 false（fail-closed）
```

### Coordinator
```java
public static final Set<String> ALLOWED_TOOLS = Set.of(
    "Agent", "SendMessage", "TaskStop", "TeamDelete", "TeamMerge",
    "ReadFile", "Glob", "Grep", "Bash");    // 保留读 + shell + 调度/合并
static boolean isCoordinatorTool(String name);
static boolean envEnabled();                 // NOVACODE_COORDINATOR ∈ {"1","true"}
static boolean active(boolean configEnabled);// 双锁：config && env
```

### TeamMerge
```java
public record MergeResult(boolean ok, List<String> conflicts,
                          boolean rolledBack, String message) {}
static MergeResult merge(Path gitRoot, String branch); // git merge 分支
```

## 模块设计

### TeamManager
**职责：** 团队生命周期与磁盘持久化（F1/F6）。
**对外接口：** `createTeam(name,mode,leadId,desc)`、`createTeamWith(team)`（外部队员进程接入）、
`getTeam(name)`（内存→磁盘重建）、`getTaskStore(name)`、`deleteTeam(name)`、`listTeams()`、
`closeAll()`、`detectBackend()` / `detectBackendFromEnv(tmux,iterm)`（可测版本）。
**落盘布局：** `<home>/.novacode/teams/<slug>/` 下 `config.json`（TeamFile）、
`tasks.json`（SharedTaskStore）、`inboxes/<name>.json`（FileMailBox）。
**后端检测：** Windows 恒为 IN_PROCESS（pwsh 无法跑 POSIX 命令的护栏）；否则
TMUX 环境变量非空 → TMUX，ITERM_SESSION_ID 非空 → ITERM，都无 → IN_PROCESS。

### TeamFile
**职责：** 团队配置磁盘形态（纯数据，跨进程/跨重启）。`sanitizeTeamName(name)`：
非字母数字替换为 `-` 并转小写（LLM 起的团队名可能带空格/中文）。`read/write` 容错。

### FileMailBox
**职责：** 每成员收件箱文件 + 锁文件并发协议（F4/N2/N3）。
**锁协议：** 同进程 `ReentrantLock` 串行化；文件锁 `createFile` 原子抢锁，`FileAlreadyExists`
→ 检查锁 mtime，超过 `STALE_LOCK_AGE_SECONDS=10` 视为陈旧可删除接管；未超时则指数退避
（5→80ms 带抖动）重试，总时限 `LOCK_ACQUIRE_TIMEOUT_MS=5000` 到点抛异常（不静默丢消息）。
**接口：** `send(recipient,msg)`、`readUnread(agentId)`、`markAllRead(agentId)`、`list(agentId)`。

### SharedTaskStore
**职责：** 团队共享任务板（F5 拆分产物）。每次读前 `load()` 重载磁盘保证跨进程最新（N4）。
**接口：** `create(title,desc,assignee,blocks,blockedBy,createdBy)`、`get(id)`、
`listTasks(status,assignee)`、`update(id,status,assignee,desc,addBlocks,addBlockedBy)`、
`initEmpty()`。

### TeammateRunner
**职责：** 进程内队员常驻循环（F3/F6/F7）。
**流程（`runInProcessTeammate`）：**
1. 挂 `TeammateProgress`；可选初始 prompt 注入 history。
2. `injectPendingMessages` 把未读邮箱折叠成 system-reminder。
3. 跑一轮：`agent.run(history, systemPrompt, planMode)` → `SubAgentRunner.drain` 排空。
4. 需审批且未批准：发 `plan_approval_request`（正文=计划全文），置状态 `awaiting plan approval`，
   进入 `runPlanApproval` 轮询批复；批准 → 以 `planMode=false` 重跑执行；驳回 → 仍只读重跑修订。
5. 非审批成员一轮完成：向 Lead 发 `[idle] <name> (reason: available)`。
6. 空闲轮询邮箱（500ms）：收到普通消息 → 作为用户消息续跑；收到关闭请求 →
   应答 `shutdown_response(approve=true)` 后退出循环。
7. 退出：`member.active=false`，status=completed。
**静态助手：** `drainLeadMailbox(teamMgr)`（格式化 `<team-notification>`）、
`buildTeammateAddendum(...)`、`injectPendingMessages(...)`、`createIdleNotification(...)`、
`isShutdownRequest(...)`、`buildTeammateRegistry(teamMgr,teamName,memberName)`。

### SpawnDispatcher
**职责：** 统一派发队员（F2）。`spawnTeammate(SpawnConfig)` 按 `team.mode` 分派：
- IN_PROCESS：`team.addMember` + 建 Agent（`SubAgentRunner.buildTeammateAgent`，DEFAULT 权限，
  可选 worktree 沙箱）+ `Thread.startVirtualThread(runInProcessTeammate)`。
- TMUX / ITERM：任务先写邮箱；构造 `--teammate --team-name <t> --agent-name <n>` CLI；
  tmux `new-window` / osascript iTerm 开新标签；失败显式抛错（不静默降级）。
`buildTeammateCLI(teamName, memberName, workdir)`、`shellQuote(s)`、`wakePane(paneId)`。

### TeamTools / TeamTaskTools / TaskStopTool
- **SendMessageTool**（`TeamTools`）：`to`/`content`/`type`/`request_id`/`approve`。
  名字→投递标识经 `AgentNameRegistry`；`to="*"` 广播同队其余成员；`to="lead"` 发给 Lead；
  结构化类型走 `TeamProtocol` 工厂。发送者所属团队解析：成员在表中 → 该队；
  `senderName="lead"` → 任意首队（Lead 不在 members 里）。
- **TeamCreateTool**：同名自动去重（`name-2`）；`TeamMode.detectBackend()`。
- **TeamDeleteTool**：`deleteTeam`（停所有成员 + 解绑注册表 + 删目录）。
- **TaskCreate / TaskGet / TaskList / TaskUpdate**（`TeamTaskTools`）：挂在
  `teamMgr.getTaskStore(teamName)` 上，队友共享同一份列表。
- **TaskStopTool**：跨队查成员、未在跑返回提示、在跑则 `stopMember`（fail-safe）。

### Coordinator / CoordinatorPrompt
- 工具收窄：`Agent` 新增 `setToolNameFilter(Predicate<String>)`，`computeSchemas` 中过滤。
- 指引注入：`Agent` 新增 `setCoordinatorActiveFn(Supplier<Boolean>)`；每轮 loop 开头注入
  `CoordinatorPrompt.buildReminder(iter)`（首轮全文、之后精简版，每 5 轮再补全文）。
- 双锁：`Coordinator.active(configEnabled)` = 配置开关 && `envEnabled()`。

### TeamMerge
**职责：** Lead 合并队员 worktree 分支（F5）。`merge(gitRoot, branch)`：
`git merge <branch>`；成功 → MergeResult(ok, conflicts=[], rolledBack=false)；
失败 → 用 `git status --porcelain` 读未合并文件，`git merge --abort` 回滚，
返回 MergeResult(ok=false, conflicts=冲突文件, rolledBack=true)。

### AgentTool 集成
`runAsTeammate` 路径：`team_name` 非空且注入 teamManager → 团队不存在自动建 →
成员名 = 显式 `name` 或 description 去空白转小写截断，重名加 `-2`/`-3` 去重 →
`ToolFilter.filterForTeammate` 过滤 → 注册 SendMessage + Task* 四件套 →
`buildTeammateAddendum` → 可选 worktree 隔离（ch14 WorktreeService）→
`setMemberMeta` 补齐元信息 → `SpawnDispatcher.spawnTeammate`。

### ToolFilter 集成
`ALWAYS_DISALLOWED` 追加协作工具（对普通子 Agent 隐形）：
`SendMessage, TaskCreate, TaskGet, TaskList, TaskUpdate, TeamCreate, TeamDelete, TaskStop`。
`filterForTeammate(source, spec, cwd)` = `filterForAgent(...)`（已挡掉协作工具）后再由
`runAsTeammate` 显式注册 SendMessage + Task*（队员工具集 = 只此这些协作工具）。

## 模块交互

```
ChatModel 启动
  ├─ new TeamManager()
  ├─ 注册 TeamTools / TeamTaskTools(Lead) / TaskStopTool 到 toolRegistry
  ├─ agentTool.setTeamManager(teamManager)
  ├─ coordinator 双锁 active → agent.setToolNameFilter / setCoordinatorActiveFn
  └─ LoopComplete 后 drainLeadMailbox → <team-notification> 注入 history

Lead 派员：AgentTool(team_name=…) → runAsTeammate → SpawnDispatcher
  └─ IN_PROCESS: Team.addMember → build Agent → virtual thread(runInProcessTeammate)

队员循环：injectPending → agent.run(history, sysPrompt, planMode) → drain
  ├─ LoopComplete → [idle] 通知 Lead（需审批则先发 plan_approval_request）
  ├─ 空闲轮询邮箱 → 新消息续跑 / 关闭请求 → shutdown_response → 退出
  └─ SendMessage/Task* 写共享 mailbox / tasks.json

Lead 收信：drainLeadMailbox → <team-notification> → 下一轮 prompt 里看到队员回报
Lead 合并：TeamMerge.merge(gitRoot, worktreeBranch) → 成功/冲突回滚上报
```

## 文件组织

```
src/main/java/com/novacode/teams/
├── TeamManager.java       — Team/Member/后端检测/生命周期/持久化
├── TeamFile.java          — config.json 磁盘形态 + sanitizeTeamName
├── MailMessage.java       — 消息记录（from/text/ts/read/summary/type/reqId/approve）
├── FileMailBox.java       — 邮箱文件 + 锁文件并发协议
├── SharedTaskStore.java   — 共享任务板（tasks.json）
├── TeamProtocol.java      — 结构化消息常量/工厂
├── AgentNameRegistry.java — 全局名字→投递标识
├── TeamTools.java         — SendMessage / TeamCreate / TeamDelete
├── TeamTaskTools.java     — TaskCreate / TaskGet / TaskList / TaskUpdate
├── TaskStopTool.java      — 中止队员
├── TeammateRunner.java    — 进程内队员循环 + 静态助手
├── TeammateProgress.java  — 队员进度（工具数/状态/最近活动）
├── SpawnDispatcher.java   — 按后端派发 + buildTeammateCLI + wakePane
├── Coordinator.java       — 工具收窄 + 双锁判定
├── CoordinatorPrompt.java — 调度指引（全文/精简）
└── TeamMerge.java         — 分支合并 + 冲突回滚

改动：
├── agent/Agent.java           — setToolNameFilter / setCoordinatorActiveFn + 指引注入
├── subagent/ToolFilter.java   — 协作工具隐形 + filterForTeammate
├── subagent/AgentTool.java    — team_name/name/plan_mode_required → runAsTeammate
├── subagent/SubAgentRunner.java — buildTeammateAgent 助手
├── config/ConfigLoader.java   — enable_coordinator_mode 字段 + loadConfig
├── App.java                   — coordinator 标志传递 + --teammate 入口
└── ui/ChatModel.java          — TeamManager + 团队工具 + 通知排空 + coordinator 装配
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 团队目录位置 | 用户主目录 `~/.novacode/teams/<slug>` | 窗格队员是独立进程、工作目录可能被 worktree 换掉，主目录保证两边同一份 |
| 审批门槛 | `agent.run(planMode=true)` 先只读出计划；批准后再以 `planMode=false` 执行 | 复用 NovaCode 现成 planMode（只读 schema），无需可变权限检查器 |
| 邮箱并发 | 同进程内存锁 + 文件锁 + 指数退避 + 陈旧锁接管 | 同进程不浪费文件系统争抢；跨进程用文件锁隔离 |
| 摘要字段 | MailMessage 落盘自动派生正文截断摘要 | 满足消息「带摘要」要求，Lead 扫收件箱免读全文 |
| 协作工具隐形 | 进 `ALWAYS_DISALLOWED`，队员路径再显式注册 | 主入口/普通子 Agent 天然看不到，无需额外开关 |
| 后端降级 | 检测出 TMUX/ITERM 但不可用 → 显式抛错 | 「不静默降级」：宁可失败也不悄悄换进程内 |
| 消息缺省语义 | `approved()` 缺省 false；关闭应答统一同意（空闲中无半截活） | fail-closed，避免误判 |
| 依赖写入 | 只追加去重（add_blocks/add_blocked_by），不做复杂拓扑 | 本步明确不做复杂约束 |
| coordinator 指引 | 首轮全文 + 精简版每轮 + 每 5 轮补全文 | 8KB 全文逐条追加会吃掉收窄省下的上下文 |
