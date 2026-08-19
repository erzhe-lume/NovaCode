# NovaCode — 架构设计

## 技术选型

| 关注点 | 选型 | 版本 | 理由 |
|--------|------|------|------|
| 语言 | Java | 21 LTS | 虚拟线程简化异步、强类型、跨平台 |
| 终端控制 | JLine 3 | 3.26.1 | raw mode、终端尺寸、ANSI 输出、信号监听 |
| YAML 配置 | SnakeYAML | 2.2 | 最成熟的 Java YAML 库 |
| HTTP/SSE | java.net.http.HttpClient | JDK 内置 | 零额外依赖，BodyHandlers.ofLines() 处理 SSE |
| JSON | Jackson | 2.17 | 标准 JSON 解析 |
| Markdown→ANSI | CommonMark-Java | 0.21 | AST 解析 + 自定义 Visitor 输出 ANSI |
| 构建 | Maven | 3.9 | 传统 Java 构建, shade plugin 打 fat jar |

## 项目结构

```
novacode/
├── pom.xml
├── config.yaml                        # 默认配置模板
├── docs/                              # 设计文档
├── src/main/java/com/novacode/
│   ├── App.java                       # 入口：加载配置 → 选择 → 启动 TUI
│   ├── config/
│   │   ├── ProviderConfig.java        # POJO 6 字段
│   │   └── ConfigLoader.java          # YAML 加载 + ${ENV} 替换 + 校验
│   ├── protocol/
│   │   ├── LlmAdapter.java            # 统一接口
│   │   ├── StreamEvent.java           # sealed interface 事件类型
│   │   ├── AnthropicAdapter.java      # Messages API + SSE
│   │   └── OpenAiAdapter.java         # Chat Completions + SSE
│   ├── model/
│   │   ├── ChatMessage.java           # 消息模型 (role + content + isError)
│   │   └── ChatHistory.java           # 内存对话历史
│   └── ui/
│       ├── Ansi.java                  # ANSI 转义码常量
│       ├── KeyReader.java             # 终端按键解析（多字节 ESC 序列）
│       ├── TerminalUI.java            # 主控：raw mode、渲染循环
│       ├── screen/
│       │   ├── Screen.java            # 抽象屏幕基类
│       │   ├── SelectorScreen.java    # Provider 选择
│       │   └── ChatScreen.java        # 聊天主界面
│       ├── widget/
│       │   ├── Banner.java            # ASCII 横幅
│       │   ├── MessageList.java       # 对话区
│       │   ├── InputArea.java         # 输入组件
│       │   └── StatusBar.java         # 状态栏
│       └── render/
│           └── AnsiMarkdownRenderer.java  # Markdown→ANSI
```

## 核心架构

### 协议适配层

```java
// 统一接口
public interface LlmAdapter {
    BlockingQueue<StreamEvent> streamChat(
        String systemPrompt,
        List<ChatMessage> history,
        ProviderConfig config
    );
}

// 事件类型（封闭接口）
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record Done(String fullText, long elapsedMs) implements StreamEvent {}
    record Error(Throwable error) implements StreamEvent {}
}
```

- **AnthropicAdapter** — POST `/v1/messages`，解析 `content_block_start/delta/stop` 事件，
  通过 index 区分 thinking block 与 text block，thinking 路由到 ThinkingDelta
- **OpenAiAdapter** — POST `/v1/chat/completions`，解析 `data: {...}` 行，
  `[DONE]` 作为终止标记

### TUI 渲染循环

```
┌─ TerminalUI 主循环 (~30fps) ───────────────────────────┐
│                                                         │
│  1. terminal.getWidth()/getHeight() → 检测 resize       │
│  2. keyReader.readKey() → 非阻塞读按键                   │
│  3. currentScreen.handleInput(key) → 处理输入           │
│  4. currentScreen.tick() → 轮询流式队列                  │
│  5. currentScreen.render() → 生成 ANSI 输出字符串        │
│  6. terminal.writer().write(output) → 写入终端           │
│  7. Thread.sleep(32) → 帧率限制                          │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### ChatScreen 状态机

```
    ┌──────────┐   Enter 提交    ┌──────────┐
    │  READY   │ ───────────────→ │ WAITING  │
    │ (可输入)  │                  │ (等首token)│
    └──────────┘                  └──────────┘
         ↑                             │
         │                     收到 TextDelta
         │                             ↓
         │                        ┌──────────┐
         │  Done / Error          │STREAMING │
         └────────────────────────│ (逐字显示) │
                                  └──────────┘
```

### 流式数据流

```
用户按 Enter
  → InputArea.submit(text)
  → ChatScreen: history.addUser(text), state=WAITING, startTimer
  → adapter.streamChat(system, history, config) → 返回 BlockingQueue
  → 后台虚拟线程: HTTP SSE 请求 → 逐行解析 → queue.offer(event)

渲染循环 tick():
  → queue.poll() → 处理事件
  → TextDelta    → streamingBuffer.append → messageList.setStreamingText
  → ThinkingDelta → 丢弃
  → Done         → markdownRenderer.render(fullText) → history.addAssistant
  → Error        → history.addError → 红色展示
```

### Markdown 渲染

使用 CommonMark-Java 解析为 AST，遍历节点输出 ANSI 格式化：

| Markdown 元素 | ANSI 效果 |
|--------------|----------|
| `# Heading` | Bold + Cyan |
| `**bold**` | Bold |
| `*italic*` | Italic |
| `` `code` `` | 暗色背景 + 绿色文字 |
| ` ```code``` ` | 暗色背景块 + 语言标签 |
| `- list item` | `• ` 前缀，缩进 |
| `1. ordered` | `N. ` 前缀 |
| `> quote` | Dim + `│ ` 前缀 |
| `[link](url)` | 蓝色下划线 + URL 引用 |

## 界面布局

```
╭──────────────────────────────────────────╮
│   ╱|、                                   │
│  (˚ˎ 。7                                 │  ← Banner (固定)
│   |、˜〵                                 │
│   じしˍ,)ノ                              │
│  NovaCode v1.0.0                         │
│  /path/to/working/dir                    │
╰──────────────────────────────────────────╯
  Ready. Type your message below.            ← 就绪提示

  ── You ──                                  ← 对话区
  Hello!                                    (自适应高度)
  ── Nova ──
  Hi! How can I help you?
  ────────────────────────────────────────

╭──────────────────────────────────────────╮
│ ❯ Send a message...                     │  ← 输入框 (1-10行)
╰──────────────────────────────────────────╯
  Claude Sonnet            claude-sonnet-4-5 ← 状态栏
```
