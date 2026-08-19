# NovaCode — 配置与协议

## 配置文件 (`config.yaml`)

### 格式

```yaml
providers:
  - name: "Claude Sonnet"         # 必填：可读名称，用于选择界面和状态栏
    protocol: anthropic           # 必填："anthropic" | "openai"
    api_key: "${ANTHROPIC_API_KEY}"  # 必填：支持 ${ENV_VAR} 替换
    model: "claude-sonnet-4-5-20250929"  # 必填：模型 ID
    base_url: "https://api.anthropic.com"  # 可选：自定义端点（默认官方地址）
    thinking: false               # 可选：是否开启扩展思考（默认 false）

  - name: "GPT-4o"
    protocol: openai
    api_key: "${OPENAI_API_KEY}"
    model: "gpt-4o"
    # base_url 默认 https://api.openai.com
    thinking: false
```

### 加载流程

```
1. 从命令行参数或默认路径找到 config.yaml
2. SnakeYAML 解析为 ConfigFile POJO
3. 遍历 providers[]，逐项校验：
   - name, protocol, api_key, model 必填
   - protocol ∈ {anthropic, openai}
   - api_key 中的 ${VAR} 替换为 System.getenv("VAR")
   - base_url 为空时填充协议默认地址
4. 校验失败 → System.err 输出清晰错误 → System.exit(1)
5. 校验通过 → 返回 List<ProviderConfig>
```

### 工作目录查找顺序

1. `--config` 命令行参数指定的路径
2. 当前工作目录下的 `config.yaml`
3. （后续可扩展 `~/.config/novacode/config.yaml`）

---

## Anthropic 协议适配

### 请求

```
POST {base_url}/v1/messages
Headers:
  x-api-key: {api_key}
  anthropic-version: 2023-06-01
  Content-Type: application/json
Body:
{
  "model": "...",
  "max_tokens": 4096,
  "system": "<system prompt>",
  "messages": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "stream": true,
  "thinking": {"type": "enabled", "budget_tokens": 10000}  // 或 {"type": "disabled"}
}
```

### SSE 事件解析

```
event: message_start
data: {"type":"message_start",...}
      → 流式开始

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
      → 记录 index → block 类型映射

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":""}}
      → 标记 index=1 为 thinking block

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
      → TextDelta("Hello")

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"Let me..."}}
      → ThinkingDelta("Let me...")  ← UI 丢弃

event: ping
data: {"type":"ping"}
      → 忽略

event: message_stop
data: {"type":"message_stop"}
      → 流结束标记
```

### Thinking Block 识别

通过 `content_block_start` 的 `content_block.type` 字段识别。后续 `content_block_delta`
的 `delta.type` 为 `thinking_delta` 时路由到 `ThinkingDelta`，
`text_delta` 时路由到 `TextDelta`。

---

## OpenAI 协议适配

### 请求

```
POST {base_url}/v1/chat/completions
Headers:
  Authorization: Bearer {api_key}
  Content-Type: application/json
Body:
{
  "model": "...",
  "max_tokens": 4096,
  "messages": [
    {"role": "system", "content": "<system prompt>"},
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "stream": true,
  "reasoning": {"effort": "medium"}  // 当 thinking: true 时
}
```

### SSE 事件解析

```
data: {"id":"...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hello"}}]}
      → TextDelta("Hello")

data: {"id":"...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
      → 最后一帧

data: [DONE]
      → 终止标记 → Done(fullText, elapsed)
```

### 说明

- OpenAI 的 reasoning/thinking 在服务端完成，delta 中不包含思考过程
- 不会产生 ThinkingDelta 事件
- `[DONE]` 是独立的 SSE 行，解析时特殊处理

---

## 统一接口

```java
public interface LlmAdapter {
    /**
     * 发起流式对话请求。
     * 在后台线程中运行，通过 BlockingQueue 向主线程推送事件。
     *
     * @param systemPrompt 系统级指令
     * @param history      完整对话历史（不包含 system role 的消息）
     * @param config       Provider 配置
     * @return 阻塞队列，接收 StreamEvent（TextDelta/ThinkingDelta/Done/Error）
     */
    BlockingQueue<StreamEvent> streamChat(
        String systemPrompt,
        List<ChatMessage> history,
        ProviderConfig config
    );
}
```

上层（ChatScreen）只依赖 `LlmAdapter` 接口，不感知具体协议。新增协议只需实现该接口。
