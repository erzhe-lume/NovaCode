# NovaCode

> 一个从零实现的终端 AI 编程 Agent（Java 21）。多协议 LLM 客户端 · Function Calling 工具系统 · 五层权限 · 上下文压缩 · 跨会话记忆 · 多 Agent 协作。

NovaCode 是一个 **Coding Agent / Terminal AI Assistant**：你用自然语言下达指令，它在一个 Agent Loop 里自己调用 LLM、自己决定用哪个工具（读文件 / 写文件 / 跑命令 / 搜索）、看结果再决定下一步，直到任务完成。

区别于"聊天机器人"，NovaCode 具备真实的工具执行能力，并通过分层架构覆盖了 Coding Agent 的核心工程问题：**安全（五层权限）、成本（上下文两层压缩）、记忆（跨会话自动沉淀）、扩展（Slash / Skill / Hook / MCP）、协作（SubAgent + Git Worktree + Agent Teams）**。

---

## ✨ 特性

- **多协议 LLM 支持**：`LlmClient` 抽象了 Anthropic 与 OpenAI-compatible（DeepSeek / GPT / 本地模型）两套流式协议，配置切换、零代码改动。
- **ReAct Agent Loop**：迭代上限 / 连续未知工具 / 流超时 / 用户取消，四重停止条件保证任务必然收敛。
- **Function Calling 工具系统**：6 个内置工具 + MCP 远程工具统一注册，READ 并发批次 / WRITE+COMMAND 串行的分批执行。
- **五层权限拦截**：黑名单 → 路径沙箱 → 三级规则引擎 → 权限模式 → 人工确认（HITL），deny 恒优先、短路返回。
- **上下文两层压缩**：大工具结果落盘（预览 + 路径）+ 自动摘要（LLM 生成结构化摘要 + 边界标记），熔断保护。
- **跨会话记忆**：四类笔记（user / feedback / project / reference）两级作用域，异步 LLM 提取，索引注入 System Prompt。
- **扩展机制**：Slash 命令（拦截快车道）、Skill（行为可插拔）、Hook（15 个生命周期事件）、MCP（工具可插拔）。
- **多 Agent 协作**：SubAgent 委派（上下文隔离）→ Git Worktree 文件隔离 → Agent Teams 协作（邮箱通信 + Coordinator 统筹调度）。
- **Elm 架构 TUI**：纯函数状态转移（Model / Message / UpdateResult），比命令式渲染更可控。

## 🏗 架构

```
┌─────────────────────────────────────────┐
│ 交互层   tui/tea · command · ui          │  TEA 终端界面、Slash 命令、ChatModel
│ 引擎层   agent · protocol · model        │  Agent Loop、LLM 客户端、事件流
│ 工具层   tool · mcp · hook               │  内置工具、MCP 接入、生命周期 Hook
│ 记忆层   context · memory · session      │  上下文压缩、自动记忆、会话持久化
│ 安全层   permission                      │  五层权限拦截（贯穿全层）
└─────────────────────────────────────────┘
```

- 5 层划分、每层独立可替换：加 MCP / Hook / Teams 时，Agent 核心循环一行未改。
- 125 个 Java 源文件，25 个包，覆盖 Coding Agent 完整光谱。

## 🛠 技术栈

| 关注点 | 选型 |
|--------|------|
| 语言 | Java 21（虚拟线程并发批次） |
| 终端控制 | JLine 3 |
| 流式 HTTP | JDK `java.net.http`（SSE） |
| JSON / YAML | Jackson databind + dataformat-yaml |
| Markdown 渲染 | CommonMark-Java |
| 构建 | Maven（shade 打包可执行 jar） |

## 🚀 快速开始

```bash
# 1. 准备 JDK 21 + Maven
# 2. 配置 LLM provider（模板见 config.example.yaml）
cp config.example.yaml config.yaml
#    编辑 config.yaml 填入你的 api_key

# 3. 编译并打包
mvn -q package

# 4. 运行
java -jar target/novacode-1.0.0.jar
```

`config.yaml` 支持多 provider，配置后启动可切换；单 provider 直入对话。

## 📖 目录结构

```
src/main/java/com/novacode/
├── agent/        Agent Loop、事件流、停止条件
├── command/      Slash 命令框架
├── config/       配置加载与校验
├── context/      上下文压缩（落盘 + 摘要 + 熔断）
├── hook/         Hook 生命周期引擎
├── mcp/          MCP 客户端（stdio / HTTP）
├── memory/       自动记忆（四类两级）
├── model/        模型 / 工具 / 会话领域模型
├── permission/   五层权限引擎
├── prompt/       System Prompt 构建、Plan Mode 提醒
├── protocol/     LLM 协议适配（Anthropic / OpenAI-compat）
├── session/      会话持久化（JSONL）
├── skill/        Skill 系统
├── subagent/     子 Agent 委派
├── teams/        Agent Teams 协作（邮箱 / Coordinator / Worktree）
├── tool/         工具注册表 + 6 内置工具
├── tui/          Elm 架构终端界面
├── ui/           渲染器、控件、屏幕
└── worktree/     Git Worktree 隔离
```

## 📜 License

暂未指定（可在 GitHub 网页端为仓库选择 License 模板）
