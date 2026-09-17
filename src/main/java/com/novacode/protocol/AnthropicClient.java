package com.novacode.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Anthropic Messages API client with SSE streaming and full tool calling. */
public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_RETRIES = 3;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final String systemPrompt;
    private List<Map<String, Object>> tools = List.of();

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        this.apiKey = cfg.getApiKey();
        this.baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        this.model = cfg.getModel();
        this.maxTokens = Math.max(1024, cfg.getMaxTokens());
        this.systemPrompt = systemPrompt;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }

    @Override
    public BlockingQueue<StreamEvent> stream(List<ChatMessage> history, String sp) {
        String prompt = sp != null ? sp : systemPrompt;
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try { doStream(history, prompt, queue); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) {
                try { queue.put(new StreamEvent.Error(e.getMessage())); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        return queue;
    }

    private void doStream(List<ChatMessage> history, String prompt, BlockingQueue<StreamEvent> queue)
            throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        // Prompt caching：system 断点把 tools + system 前缀整体标记为可缓存
        // （Anthropic 缓存按前缀匹配，tools 排在 system 之前自动包含）。
        if (prompt != null && !prompt.isBlank()) {
            body.put("system", List.of(Map.of(
                    "type", "text", "text", prompt,
                    "cache_control", Map.of("type", "ephemeral"))));
        }
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        body.put("messages", buildMessages(history));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        // 429/5xx/连接失败：指数退避重试（1s/2s/4s），瞬态错误不再中断整个任务。
        HttpResponse<java.io.InputStream> response = null;
        IOException lastIo = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) Thread.sleep(1000L * (1L << (attempt - 1)));
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                lastIo = e;
                continue;
            }
            if (response.statusCode() == 200 || !retryableStatus(response.statusCode())) break;
            try (var is = response.body()) { is.readAllBytes(); } // 排干错误体
        }
        if (response == null) {
            throw lastIo != null ? lastIo : new IOException("request failed after retries");
        }

        if (response.statusCode() != 200) {
            String errBody;
            try (var is = response.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            queue.put(new StreamEvent.Error("HTTP " + response.statusCode() + ": " + errBody));
            return;
        }

        // Per-index block state for tool_use and thinking blocks
        Map<Integer, Boolean> thinkingBlocks = new HashMap<>();
        Map<Integer, String> toolIds = new HashMap<>();
        Map<Integer, String> toolNames = new HashMap<>();
        Map<Integer, StringBuilder> toolArgs = new HashMap<>();
        int[] usageInput = {0}, usageOutput = {0}, usageCacheRead = {0}, usageCacheWrite = {0};
        boolean[] usageEmitted = {false};
        String[] stopReason = {null};

        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line, eventType = "", dataBuf = "";
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!dataBuf.isEmpty()) {
                        processSseEvent(eventType, dataBuf, queue, thinkingBlocks,
                                toolIds, toolNames, toolArgs,
                                usageInput, usageOutput, usageCacheRead, usageCacheWrite,
                                usageEmitted, stopReason);
                    }
                    eventType = ""; dataBuf = "";
                    continue;
                }
                if (line.startsWith("event: ")) eventType = line.substring(7).trim();
                else if (line.startsWith("data: ")) dataBuf = dataBuf + (dataBuf.isEmpty() ? "" : "\n") + line.substring(6);
            }
            if (!dataBuf.isEmpty()) {
                processSseEvent(eventType, dataBuf, queue, thinkingBlocks,
                        toolIds, toolNames, toolArgs,
                        usageInput, usageOutput, usageCacheRead, usageCacheWrite,
                        usageEmitted, stopReason);
            }
        }

        queue.put(new StreamEvent.StreamEnd(stopReason[0] != null ? stopReason[0] : "end_turn"));
    }

    void processSseEvent(String eventType, String data, BlockingQueue<StreamEvent> queue,
                                  Map<Integer, Boolean> thinkingBlocks,
                                  Map<Integer, String> toolIds,
                                  Map<Integer, String> toolNames,
                                  Map<Integer, StringBuilder> toolArgs,
                                  int[] usageInput, int[] usageOutput,
                                  int[] usageCacheRead, int[] usageCacheWrite,
                                  boolean[] usageEmitted, String[] stopReason) {
        try {
            JsonNode json = MAPPER.readTree(data);
            String type = json.has("type") ? json.get("type").asText() : "";

            switch (type) {
                case "message_start" -> {
                    JsonNode usage = json.path("message").path("usage");
                    if (usage.has("input_tokens")) usageInput[0] = usage.get("input_tokens").asInt();
                    if (usage.has("cache_read_input_tokens")) usageCacheRead[0] = usage.get("cache_read_input_tokens").asInt();
                    if (usage.has("cache_creation_input_tokens")) usageCacheWrite[0] = usage.get("cache_creation_input_tokens").asInt();
                }
                case "content_block_start" -> {
                    int idx = json.path("index").asInt(-1);
                    JsonNode block = json.path("content_block");
                    String bt = block.path("type").asText();
                    if ("thinking".equals(bt)) {
                        thinkingBlocks.put(idx, true);
                    } else if ("tool_use".equals(bt)) {
                        toolIds.put(idx, block.path("id").asText(""));
                        toolNames.put(idx, block.path("name").asText(""));
                        toolArgs.put(idx, new StringBuilder());
                    }
                }
                case "content_block_delta" -> {
                    int idx = json.path("index").asInt(-1);
                    boolean isThinking = thinkingBlocks.getOrDefault(idx, false);
                    JsonNode delta = json.path("delta");
                    String dt = delta.path("type").asText();
                    if ("text_delta".equals(dt) && !isThinking) {
                        queue.put(new StreamEvent.TextDelta(delta.path("text").asText("")));
                    } else if ("thinking_delta".equals(dt)) {
                        queue.put(new StreamEvent.ThinkingDelta(delta.path("thinking").asText("")));
                    } else if ("input_json_delta".equals(dt)) {
                        StringBuilder sb = toolArgs.get(idx);
                        if (sb != null) sb.append(delta.path("partial_json").asText(""));
                    }
                }
                case "content_block_stop" -> {
                    int idx = json.path("index").asInt(-1);
                    String name = toolNames.get(idx);
                    if (name != null) {
                        String callId = toolIds.getOrDefault(idx, "toolu_" + idx);
                        String rawArgs = toolArgs.containsKey(idx) ? toolArgs.get(idx).toString() : "{}";
                        Map<String, Object> args;
                        try {
                            args = MAPPER.readValue(rawArgs.isEmpty() ? "{}" : rawArgs,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            args = Map.of();
                        }
                        queue.put(new StreamEvent.ToolCallComplete(callId, name, args));
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = json.path("delta");
                    if (delta.has("stop_reason")) stopReason[0] = delta.get("stop_reason").asText();
                    JsonNode usage = json.path("usage");
                    if (usage.has("output_tokens")) usageOutput[0] = usage.get("output_tokens").asInt();
                    if (usage.has("cache_read_input_tokens")) usageCacheRead[0] = usage.get("cache_read_input_tokens").asInt();
                    if (usage.has("cache_creation_input_tokens")) usageCacheWrite[0] = usage.get("cache_creation_input_tokens").asInt();
                }
                case "message_stop" -> {
                    if (!usageEmitted[0] && (usageInput[0] > 0 || usageOutput[0] > 0)) {
                        queue.put(new StreamEvent.Usage(usageInput[0], usageOutput[0],
                                usageCacheRead[0], usageCacheWrite[0]));
                        usageEmitted[0] = true;
                    }
                }
                case "ping" -> {}
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Malformed SSE line — ignore and keep the stream alive.
        }
    }

    /** Build Anthropic Messages API message array from the shared history.
     *
     *  <p>连续 TOOL 消息合并为一条 user 消息（多个 tool_result blocks，Anthropic 规范）；
     *  末条消息的最后一个 block 上打缓存断点 —— 增量缓存：服务端按前缀匹配，上一轮
     *  写过的缓存条目自然命中，本轮在末尾续写新条目。全部 block 用可变 Map 构建，
     *  便于末条打点。</p>
     */
    List<Map<String, Object>> buildMessages(List<ChatMessage> history) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        List<Map<String, Object>> pendingResults = new ArrayList<>();

        for (var m : history) {
            if (m.getRole() == ChatMessage.Role.TOOL) {
                pendingResults.add(block("tool_result",
                        "tool_use_id", m.getToolCallId() != null ? m.getToolCallId() : "",
                        "content", m.getContent() != null ? m.getContent() : ""));
                continue;
            }
            flushToolResults(msgs, pendingResults);
            switch (m.getRole()) {
                case USER -> msgs.add(Map.of("role", "user", "content",
                        new ArrayList<>(List.of(textBlock(m.getContent())))));
                case ASSISTANT -> {
                    if (m.hasToolCalls()) {
                        var content = new ArrayList<Map<String, Object>>();
                        String text = m.getContent() != null ? m.getContent() : "";
                        if (!text.isEmpty()) content.add(textBlock(text));
                        for (var tc : m.getToolCalls()) {
                            content.add(block("tool_use",
                                    "id", tc.id(),
                                    "name", tc.name(),
                                    "input", tc.arguments() != null ? tc.arguments() : Map.of()));
                        }
                        msgs.add(Map.of("role", "assistant", "content", content));
                    } else {
                        msgs.add(Map.of("role", "assistant", "content",
                                new ArrayList<>(List.of(textBlock(m.getContent())))));
                    }
                }
                default -> msgs.add(Map.of("role", "user", "content",
                        new ArrayList<>(List.of(textBlock(m.getContent())))));
            }
        }
        flushToolResults(msgs, pendingResults);

        if (!msgs.isEmpty()) {
            markLastBlockCacheable(msgs.get(msgs.size() - 1));
        }
        return msgs;
    }

    /** 把积压的 tool_result 作为一个 user 消息 flush 出去。 */
    private static void flushToolResults(List<Map<String, Object>> msgs,
                                         List<Map<String, Object>> pendingResults) {
        if (pendingResults.isEmpty()) return;
        msgs.add(Map.of("role", "user", "content", new ArrayList<>(pendingResults)));
        pendingResults.clear();
    }

    /** 在消息 content 的最后一个 block 上打缓存断点。 */
    private static void markLastBlockCacheable(Map<String, Object> msg) {
        if (msg.get("content") instanceof List<?> list && !list.isEmpty()
                && list.get(list.size() - 1) instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> last = (Map<String, Object>) list.get(list.size() - 1);
            last.put("cache_control", Map.of("type", "ephemeral"));
        }
    }

    private static Map<String, Object> textBlock(String text) {
        return block("text", "text", text != null ? text : "");
    }

    /** 可变 content block（末条消息打 cache_control 需要 put）。 */
    private static Map<String, Object> block(String type, Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 429/408/529(overloaded)/5xx 视为瞬态，可重试。包私有以便测试。 */
    static boolean retryableStatus(int code) {
        return code == 408 || code == 429 || code == 529 || (code >= 500 && code <= 599);
    }
}
