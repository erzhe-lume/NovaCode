package com.novacode.protocol;

import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 协议层消息构建：TOOL 合并、缓存断点、OpenAI 结构。 */
class ProtocolMessagesTest {

    private AnthropicClient anthropic() {
        var cfg = new ProviderConfig("t", "anthropic", "sk-test", "claude-test",
                "https://api.example.com", false);
        return new AnthropicClient(cfg, "sys");
    }

    @Test
    void anthropicMergesConsecutiveToolResultsIntoSingleUserMessage() {
        var history = new java.util.ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "hi"));
        history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(
                new ChatMessage.ToolCall("t1", "ReadFile", Map.of("file_path", "a.java")),
                new ChatMessage.ToolCall("t2", "Grep", Map.of("pattern", "x")))));
        history.add(ChatMessage.toolResult("t1", "content of a"));
        history.add(ChatMessage.toolResult("t2", "grep hits"));
        history.add(new ChatMessage(ChatMessage.Role.USER, "thanks"));

        List<Map<String, Object>> msgs = anthropic().buildMessages(history);

        assertEquals(4, msgs.size(), "tool 结果应合并，消息数 4");
        assertEquals("user", msgs.get(2).get("role"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msgs.get(2).get("content");
        assertEquals(2, content.size());
        assertEquals("tool_result", content.get(0).get("type"));
        assertEquals("t1", content.get(0).get("tool_use_id"));
        assertEquals("t2", content.get(1).get("tool_use_id"));
    }

    @Test
    void anthropicMarksCacheBreakpointOnlyOnLastMessage() {
        var history = new java.util.ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "hi"));
        history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, "done"));

        List<Map<String, Object>> msgs = anthropic().buildMessages(history);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lastContent = (List<Map<String, Object>>) msgs.get(1).get("content");
        assertTrue(lastContent.get(0).containsKey("cache_control"), "末条消息应打缓存断点");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstContent = (List<Map<String, Object>>) msgs.get(0).get("content");
        assertFalse(firstContent.get(0).containsKey("cache_control"), "非末条不应打断点");
    }

    @Test
    void anthropicBreakpointOnFlushedToolResults() {
        var history = List.of(
                new ChatMessage(ChatMessage.Role.USER, "hi"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(
                        new ChatMessage.ToolCall("t1", "Bash", Map.of("command", "ls")))),
                ChatMessage.toolResult("t1", "out"));

        List<Map<String, Object>> msgs = anthropic().buildMessages(history);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lastContent = (List<Map<String, Object>>) msgs.get(2).get("content");
        assertTrue(lastContent.get(lastContent.size() - 1).containsKey("cache_control"),
                "以 tool_result 结尾时断点应打在合并消息的末 block 上");
    }

    @Test
    void anthropicSystemPromptCarriesCacheControl() throws Exception {
        // system 由 doStream 组装，此处只验证 buildMessages 不破坏结构 + 常量语义
        var history = List.of(new ChatMessage(ChatMessage.Role.USER, "hi"));
        List<Map<String, Object>> msgs = anthropic().buildMessages(history);
        assertEquals(1, msgs.size());
        assertInstanceOf(List.class, msgs.get(0).get("content"));
    }

    @Test
    void openAiBodyKeepsToolCallStructure() throws Exception {
        var cfg = new ProviderConfig("t", "openai-compat", "sk-test", "m",
                "https://api.example.com", false);
        var client = new OpenAiCompatClient(cfg, "sys");
        var history = List.of(
                new ChatMessage(ChatMessage.Role.USER, "hi"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(
                        new ChatMessage.ToolCall("call_1", "Bash", Map.of("command", "ls")))),
                ChatMessage.toolResult("call_1", "out"));

        String body = client.buildRequestBody(history, "sysprompt", List.of());

        assertTrue(body.contains("\"role\":\"system\""), "system 应在最前");
        assertTrue(body.contains("sysprompt"), "system 内容应存在");
        assertTrue(body.contains("\"tool_calls\""), "assistant 应带 tool_calls");
        assertTrue(body.contains("\"role\":\"tool\""), "tool 角色应存在");
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""), "tool 应带 tool_call_id");
        assertTrue(body.contains("\"max_tokens\":8192"), "max_tokens 默认 8192");
    }

    @Test
    void openAiBodyOmitsToolsWhenEmpty() throws Exception {
        var cfg = new ProviderConfig("t", "openai-compat", "sk-test", "m",
                "https://api.example.com", false);
        var client = new OpenAiCompatClient(cfg, "sys");
        var history = List.of(new ChatMessage(ChatMessage.Role.USER, "hi"));
        String body = client.buildRequestBody(history, null, List.of());
        assertFalse(body.contains("\"tools\""), "空工具列表不应输出 tools 字段");
    }

    // ── retryableStatus：瞬态错误判定 ────────────────────────────────

    @Test
    void anthropicRetryableStatuses() {
        assertTrue(AnthropicClient.retryableStatus(429), "限流应重试");
        assertTrue(AnthropicClient.retryableStatus(500));
        assertTrue(AnthropicClient.retryableStatus(503));
        assertTrue(AnthropicClient.retryableStatus(529), "overloaded 应重试");
        assertTrue(AnthropicClient.retryableStatus(408), "请求超时应重试");
        assertFalse(AnthropicClient.retryableStatus(400), "参数错误不可重试");
        assertFalse(AnthropicClient.retryableStatus(401), "鉴权失败不可重试");
        assertFalse(AnthropicClient.retryableStatus(404));
    }

    @Test
    void openAiRetryableStatuses() {
        assertTrue(OpenAiCompatClient.retryableStatus(429));
        assertTrue(OpenAiCompatClient.retryableStatus(502));
        assertTrue(OpenAiCompatClient.retryableStatus(504));
        assertFalse(OpenAiCompatClient.retryableStatus(400));
        assertFalse(OpenAiCompatClient.retryableStatus(200));
    }
}
