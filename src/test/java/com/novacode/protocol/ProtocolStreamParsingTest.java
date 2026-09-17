package com.novacode.protocol;

import com.novacode.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 协议层 SSE 解析健壮性：tool_call 增量累积、usage 双格式、损坏行容错。 */
class ProtocolStreamParsingTest {

    private OpenAiCompatClient openAi() {
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        return new OpenAiCompatClient(cfg, "sys");
    }

    private AnthropicClient anthropic() {
        var cfg = new ProviderConfig("t", "anthropic", "k", "m", "https://example.com", false);
        return new AnthropicClient(cfg, "sys");
    }

    private static StreamEvent poll(LinkedBlockingQueue<StreamEvent> q) throws Exception {
        return q.poll(1, TimeUnit.SECONDS);
    }

    // ── OpenAI ───────────────────────────────────────────────────────

    @Test
    void openAiTextAndReasoningDeltas() throws Exception {
        var client = openAi();
        var q = new LinkedBlockingQueue<StreamEvent>();
        boolean ended = client.handleSseData(
                "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", q,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        assertFalse(ended);
        assertEquals("你好", ((StreamEvent.TextDelta) poll(q)).text());

        ended = client.handleSseData(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"想一下\"}}]}", q,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        assertFalse(ended);
        assertInstanceOf(StreamEvent.ThinkingDelta.class, poll(q));
    }

    @Test
    void openAiToolCallAccumulatesAcrossChunksAndFlushes() throws Exception {
        var client = openAi();
        var q = new LinkedBlockingQueue<StreamEvent>();
        var names = new HashMap<Integer, StringBuilder>();
        var args = new HashMap<Integer, StringBuilder>();
        var ids = new HashMap<Integer, String>();

        client.handleSseData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"ReadFile\"}}]}}]}", q,
                names, args, ids, new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        client.handleSseData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"{\\\"file_\"}}]}}]}", q,
                names, args, ids, new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        client.handleSseData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"path\\\":\\\"a\\\"}\"}}]}}]}", q,
                names, args, ids, new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        boolean ended = client.handleSseData(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", q,
                names, args, ids, new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        assertFalse(ended, "tool_calls 结束后流由 [DONE] 收尾");

        // 队列里先有 ToolCallStart/ToolCallDelta，跳到 ToolCallComplete
        StreamEvent evt = poll(q);
        while (evt instanceof StreamEvent.ToolCallStart || evt instanceof StreamEvent.ToolCallDelta) {
            evt = poll(q);
        }
        var call = (StreamEvent.ToolCallComplete) evt;
        assertEquals("call_1", call.toolId());
        assertEquals("ReadFile", call.toolName());
        assertEquals("a", call.arguments().get("file_path"), "跨 chunk 的 JSON 参数应正确累积解析");
    }

    @Test
    void openAiUsageBothProviderFormats() throws Exception {
        // DeepSeek 顶层字段：hit + miss == prompt_tokens
        var client = openAi();
        var q = new LinkedBlockingQueue<StreamEvent>();
        int[] in = new int[1], out = new int[1], cr = new int[1], cw = new int[1];
        boolean[] emitted = new boolean[1];
        client.handleSseData("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                + "\"completion_tokens\":5,\"prompt_cache_hit_tokens\":80,"
                + "\"prompt_cache_miss_tokens\":20}}", q,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), in, out, cr, cw, emitted);
        var usage = (StreamEvent.Usage) poll(q);
        assertEquals(100, usage.inputTokens());
        assertEquals(80, usage.cacheRead(), "DeepSeek hit 字段应映射到 cacheRead");
        assertEquals(20, usage.cacheWrite());

        // OpenAI 嵌套 details 格式
        var q2 = new LinkedBlockingQueue<StreamEvent>();
        int[] in2 = new int[1], out2 = new int[1], cr2 = new int[1], cw2 = new int[1];
        boolean[] emitted2 = new boolean[1];
        client.handleSseData("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                + "\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":60}}}", q2,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), in2, out2, cr2, cw2, emitted2);
        var usage2 = (StreamEvent.Usage) poll(q2);
        assertEquals(60, usage2.cacheRead());
        assertEquals(0, usage2.cacheWrite());
    }

    @Test
    void openAiErrorObjectAndMalformedAndStop() throws Exception {
        var client = openAi();
        var q = new LinkedBlockingQueue<StreamEvent>();
        client.handleSseData("{\"error\":{\"message\":\"boom\"}}", q,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        assertEquals("boom", ((StreamEvent.Error) poll(q)).message());

        // 损坏行：不抛异常、不产事件
        assertFalse(client.handleSseData("not-json{{", q,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new int[1], new int[1], new int[1], new int[1], new boolean[1]));
        assertNull(poll(q), "损坏行不应产生事件");

        // stop → StreamEnd 且返回 true
        boolean ended = client.handleSseData(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", q,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new int[1], new int[1], new int[1], new int[1], new boolean[1]);
        assertTrue(ended);
        assertInstanceOf(StreamEvent.StreamEnd.class, poll(q));
    }

    // ── Anthropic ────────────────────────────────────────────────────

    @Test
    void anthropicToolUseBlockAccumulation() throws Exception {
        var client = anthropic();
        var q = new LinkedBlockingQueue<StreamEvent>();
        var thinking = new HashMap<Integer, Boolean>();
        var ids = new HashMap<Integer, String>();
        var names = new HashMap<Integer, String>();
        var args = new HashMap<Integer, StringBuilder>();
        int[] in = new int[1], out = new int[1], cr = new int[1], cw = new int[1];
        boolean[] emitted = new boolean[1];
        String[] stop = new String[1];

        client.processSseEvent("content_block_start", "{\"type\":\"content_block_start\","
                + "\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\","
                + "\"name\":\"ReadFile\"}}", q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        client.processSseEvent("content_block_delta", "{\"type\":\"content_block_delta\","
                + "\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"{\\\"file_\"}}", q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        client.processSseEvent("content_block_delta", "{\"type\":\"content_block_delta\","
                + "\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"path\\\":\\\"x\\\"}\"}}", q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        client.processSseEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);

        var call = (StreamEvent.ToolCallComplete) poll(q);
        assertEquals("tu_1", call.toolId());
        assertEquals("ReadFile", call.toolName());
        assertEquals("x", call.arguments().get("file_path"));
    }

    @Test
    void anthropicUsageAndStopReasonAndThinking() throws Exception {
        var client = anthropic();
        var q = new LinkedBlockingQueue<StreamEvent>();
        var thinking = new HashMap<Integer, Boolean>();
        var ids = new HashMap<Integer, String>();
        var names = new HashMap<Integer, String>();
        var args = new HashMap<Integer, StringBuilder>();
        int[] in = new int[1], out = new int[1], cr = new int[1], cw = new int[1];
        boolean[] emitted = new boolean[1];
        String[] stop = new String[1];

        client.processSseEvent("message_start", "{\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":50,\"cache_read_input_tokens\":30,"
                + "\"cache_creation_input_tokens\":10}}}", q, thinking, ids, names, args,
                in, out, cr, cw, emitted, stop);
        assertEquals(50, in[0]);
        assertEquals(30, cr[0]);

        client.processSseEvent("content_block_start", "{\"type\":\"content_block_start\","
                + "\"index\":0,\"content_block\":{\"type\":\"thinking\"}}",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        client.processSseEvent("content_block_delta", "{\"type\":\"content_block_delta\","
                + "\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"思考中\"}}",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        assertInstanceOf(StreamEvent.ThinkingDelta.class, poll(q), "thinking 块不走 TextDelta");

        client.processSseEvent("message_delta", "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        assertEquals("tool_use", stop[0]);
        assertEquals(9, out[0]);

        client.processSseEvent("message_stop", "{\"type\":\"message_stop\"}",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop);
        var usage = (StreamEvent.Usage) poll(q);
        assertEquals(50, usage.inputTokens());
        assertEquals(9, usage.outputTokens());
    }

    @Test
    void anthropicPingAndMalformedTolerated() throws Exception {
        var client = anthropic();
        var q = new LinkedBlockingQueue<StreamEvent>();
        var thinking = new HashMap<Integer, Boolean>();
        var ids = new HashMap<Integer, String>();
        var names = new HashMap<Integer, String>();
        var args = new HashMap<Integer, StringBuilder>();
        int[] in = new int[1], out = new int[1], cr = new int[1], cw = new int[1];
        boolean[] emitted = new boolean[1];
        String[] stop = new String[1];

        assertDoesNotThrow(() -> client.processSseEvent("ping", "{\"type\":\"ping\"}",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop));
        assertDoesNotThrow(() -> client.processSseEvent("event", "garbage{{",
                q, thinking, ids, names, args, in, out, cr, cw, emitted, stop));
        assertNull(poll(q), "ping 与损坏行都不应产生事件");
    }
}
