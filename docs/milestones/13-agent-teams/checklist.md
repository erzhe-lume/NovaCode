# 第 15 章 长期团队 Checklist

> 每一项通过运行代码或观察行为来验证。验证 = 跑 Smoke15（无 LLM 依赖，全本地）。

## 实现完整性

- [ ] 消息模型 MailMessage 已实现（验证：Smoke15「msg:」系列通过）
- [ ] 团队配置磁盘形态 TeamFile 已实现（验证：Smoke15「team: round-trip」通过）
- [ ] 邮箱 FileMailBox 已实现（验证：Smoke15「mail:」系列通过）
- [ ] 共享任务板 SharedTaskStore 已实现（验证：Smoke15「task:」系列通过）
- [ ] 协议常量 + 名称注册表已实现（验证：Smoke15「proto:」「registry:」通过）
- [ ] TeamManager 生命周期/持久化/后端检测已实现（验证：Smoke15「team:」「backend:」通过）
- [ ] 队员循环 TeammateRunner 已实现（验证：Smoke15「teammate:」通过）
- [ ] SpawnDispatcher 已实现（验证：Smoke15「spawn:」通过）
- [ ] 协作工具三件套已实现（验证：Smoke15「tools:」通过）
- [ ] Coordinator + 指引 + TeamMerge 已实现（验证：Smoke15「coord:」「merge:」通过）

## 集成

- [ ] ToolFilter 已接入——协作工具对普通子 Agent 隐形（验证：Smoke15「filter: collab hidden」通过）
- [ ] Agent 已接入 coordinator 工具过滤与指引注入（验证：Smoke15「coord: filter hides write tools」「coord: reminder」通过）
- [ ] AgentTool 已接入 team_name 派队员路径（验证：Smoke15「agenttool:」通过）
- [ ] ConfigLoader 已解析 enable_coordinator_mode（验证：Smoke15「coord: config flag」通过）
- [ ] App 已接入 --teammate 入口与 coordinator 标志传递（验证：Smoke15「app: teammate flag」通过）
- [ ] ChatModel 已装配 TeamManager + 团队工具 + lead 收件箱通知（验证：mvn compile 通过 + Smoke15「teammate: drainLeadMailbox」通过）
- [ ] 所有公开接口至少被一个真实调用方使用（验证：mvn compile + Smoke15 全部通过）

## 编译与测试

- [ ] `mvn -q compile` 无错误
- [ ] `mvn -q -DskipTests package` 成功产出 jar（--teammate 入口依赖它）
- [ ] Smoke15 编译无错误且运行 `==== PASS=N FAIL=0 ====`（N ≥ 50）

## 端到端场景

- [ ] 场景 1（团队 + 任务 + 消息全链路）：建团队 → 磁盘出现 `<home>/.novacode/teams/<slug>/` 下
  config.json / tasks.json / inboxes/ → 同一进程内用 TeamManager 按名重建团队，成员元信息与任务
  完全恢复（验证：Smoke15「team: create layout」「team: disk rebuild」）
- [ ] 场景 2（邮箱并发 + 陈旧锁）：50 个虚拟线程对同一收件箱并发发送，最终消息一条不丢；
  人为制造超过 10s 的锁文件后，新发送可接管（验证：Smoke15「mail: concurrent 50 threads」
  「mail: stale lock takeover」）
- [ ] 场景 3（任务依赖 + 过滤）：建任务 A/B，B blocked_by A；A 完成后 update 状态；按状态与
  assignee 过滤各自命中（验证：Smoke15「task: deps append dedup」「task: filter」）
- [ ] 场景 4（后端检测优先级）：TMUX 环境变量非空 → TMUX；仅 ITERM_SESSION_ID → ITERM；
  都无 → IN_PROCESS；Windows 恒 IN_PROCESS（验证：Smoke15「team: detectBackend env priority」
  「backend: windows forced in-process」）
- [ ] 场景 5（消息协议）：广播 `*` 一次送达同队其余全部成员；plan_approval_response 的
  requestId 与请求对回；无 approve 字段的应答判为不批准（fail-closed）；正文含 `[shutdown]`
  判为关闭请求（验证：Smoke15「tools: sendMessage broadcast」「proto: request id」
  「proto: approved fail-closed」「proto: shutdown detection」）
- [ ] 场景 6（git 合并）：worktree 分支干净可快进合并成功；人为制造冲突后合并失败 → 自动
  `git merge --abort` 回滚并报告冲突文件（验证：Smoke15「merge: fast-forward」
  「merge: conflict rollback reports files」）
- [ ] 场景 7（coordinator 双锁）：仅配置开关开 → 不生效；配置关 + 环境变量开 → 不生效；
  两者都开 → Agent 写文件工具不可见、只读 + shell + 调度工具可见（验证：Smoke15「coord: env gate」
  「coord: allowed/denied sets」「coord: filter hides write tools」）
- [ ] 场景 8（队员工具边界）：队员工具集 = 基础六工具 + SendMessage + Task* 四件套，不含
  Agent / TeamCreate / TeamDelete / TaskStop；普通子 Agent 工具集不含任何协作工具（验证：
  Smoke15「filter: teammate base excludes collab」「filter: collab hidden from normal subagent」）
