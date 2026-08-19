# NovaCode — 验收标准

## 验收矩阵

| AC | 需求 | 验收方法 | 实现位置 |
|----|------|----------|----------|
| AC1 | F1: 单 provider 直入；缺失密钥报错退出 | 删 key 启动 → 看到清晰错误而非崩溃 | `ConfigLoader.java` — validate() |
| AC2 | F2: 多 provider 列表选择；状态栏显示名称+模型 | 配 2 个 → ↑↓ 选 → status bar 显示正确 | `SelectorScreen.java`, `StatusBar.java` |
| AC3 | F3: Anthropic + OpenAI 均正常收发 | 分别配置 → 各发一条 → 均流式返回 | `AnthropicAdapter.java`, `OpenAiAdapter.java` |
| AC4 | F4: 携带 system prompt + 完整历史 | 先问 A、再问"刚才说的那个"→ 回答正确 | `ChatScreen.java` — submitMessage() |
| AC5 | F5: 逐字流式；thinking 不出现 | 开启 thinking → 回复中思考文本出现次数为 0 | `ChatScreen.tick()` — ThinkingDelta 丢弃 |
| AC6 | F6: 多轮上下文正确；重启历史清空 | 连续 3 轮 → 退出重进 → 历史为空 | `ChatHistory.java` — 内存存储 |
| AC7 | F7: 全 UI 布局完整 | 启动 → banner + ready + ❯ + status bar 齐全 | `ChatScreen.render()` |
| AC8 | F8: 回复结束 markdown 美化 | 模型回复含代码块/列表 → 渲染正确 | `AnsiMarkdownRenderer.java` |
| AC9 | F9: Alt+Enter 换行、Enter 提交 | 多行编辑 → Enter 提交 → 完整发送 | `InputArea.java`, `KeyReader.java` |
| AC10 | F10: /exit 或 Ctrl+C 安全退出 | 两种方式退出 → 终端无错乱 | `TerminalUI.shutdown()` |
| AC11 | F11: 错误可区分样式展示，不退出 | 错误密钥 → 红色错误 → 仍可继续输入 | `ChatScreen.tick()` — Error case |
| AC12 | F12: 实时计时 | 发消息 → "Imagining… (Ns)" → 结束总耗时 | `StatusBar.setState()`, `ChatScreen.tick()` |
| AC13 | N1: 界面不阻塞 | 等待/流式期间可看到 timer 跳动 | 渲染循环 30fps + 虚拟线程 HTTP |

## 验证步骤

### 1. 环境准备

```bash
# 确认 Java 21+
java -version

# 编辑 config.yaml，配置至少一个有效的 provider
# 支持 ${ANTHROPIC_API_KEY} 或直接填写密钥
```

### 2. 构建与启动

```bash
mvn clean package -DskipTests
java -jar target/novacode-1.0.0.jar
```

### 3. 基础对话验证（AC3, AC4, AC5, AC6）

```
> Hello, my name is Alice
  → 等待流式返回，确认逐字显示
  → 确认回复结束后文本被 markdown 渲染

> What's my name?
  → 应回答 "Alice"（证明上下文被携带）

> 发送一段让模型回复代码块的消息
  → 确认代码块以暗色背景显示
```

### 4. 错误处理验证（AC11）

```
1. 修改 config.yaml，api_key 改为无效值
2. 重启 → 发送消息 → 应看到红色错误卡片
3. 确认输入框仍然可用，可以继续输入
```

### 5. 退出验证（AC10）

```
1. 在输入框输入 /exit → 回车 → 程序退出，终端正常
2. 重新启动 → 按 Ctrl+C → 程序退出，终端正常
3. 验证终端光标可见、echo 正常
```

### 6. 多 Provider 验证（AC2）

```
1. config.yaml 配置 2 个以上 providers
2. 启动 → 出现选择列表
3. ↑↓ 键导航 → Enter 确认
4. 进入对话 → 底部状态栏显示所选 provider
```

### 7. 输入验证（AC9）

```
1. 在输入框输入多行文本（用 Alt+Enter 换行）
2. 按 Enter 提交
3. 确认完整多行文本被发送
```

## 已知限制

1. **CJK 宽度**：中文字符在终端中占 2 列宽度，输入框光标位置计算可能与实际显示有偏差
2. **超长上下文**：不做摘要/截断，超长由用户自行控制
3. **单色终端**：256 色以下的终端可能显示效果不佳
4. **Windows Terminal**：推荐使用 Windows Terminal 而非 cmd.exe
5. **无流式中断**：不支持取消正在进行的回复
6. **无自动重试**：出错仅提示，不自动重试或退避
