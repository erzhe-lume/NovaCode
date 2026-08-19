package com.novacode.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;

import java.io.BufferedReader;
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

/**
 * Hand-written HTTP client for OpenAI-compatible Chat Completions API.
 * Works with DeepSeek, vLLM, Ollama, Together, Groq, etc.
 */
public class OpenAiCompatClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** DeepSeek / OpenAI-compat standard ceiling. Explicit so the truncation boundary is predictable. */
    private static final int MAX_TOKENS = 4096;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private List<Map<String, Object>> tools = List.of();

    public OpenAiCompatClient(ProviderConfig cfg, String systemPrompt) {
        this.apiKey = cfg.getApiKey();
        this.baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        this.model = cfg.getModel();
        this.systemPrompt = systemPrompt;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }

    @Override
    public BlockingQueue<StreamEvent> stream(List<ChatMessage> history, String sp) {
        String prompt = sp != null ? sp : systemPrompt;
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try {
                doStream(history, prompt, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try { queue.put(new StreamEvent.Error(e.getMessage())); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        return queue;
    }

    private void doStream(List<ChatMessage> history, String prompt, BlockingQueue<StreamEvent> queue)
            throws Exception {

        String body = buildRequestBody(history, prompt, tools);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status != 200) {
            String errBody;
            try (var is = response.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            queue.put(new StreamEvent.Error("HTTP " + status + ": " + errBody));
            return;
        }

        // Tool call accumulation state
        var toolNames = new HashMap<Integer, StringBuilder>();
        var toolArgs = new HashMap<Integer, StringBuilder>();
        var toolIds = new HashMap<Integer, String>();
        boolean streamEnded = false;
        int[] usageInput = {0}, usageOutput = {0}, usageCacheRead = {0}, usageCacheWrite = {0};
        boolean[] usageEmitted = {false};

        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    flushToolCalls(queue, toolNames, toolArgs, toolIds);
                    if (!usageEmitted[0] && (usageInput[0] > 0 || usageOutput[0] > 0)) {
                        queue.put(new StreamEvent.Usage(usageInput[0], usageOutput[0],
                                usageCacheRead[0], usageCacheWrite[0]));
                        usageEmitted[0] = true;
                    }
                    streamEnded = true;
                    queue.put(new StreamEvent.StreamEnd("stop"));
                    break;
                }

                streamEnded = handleSseData(data, queue, toolNames, toolArgs, toolIds,
                        usageInput, usageOutput, usageCacheRead, usageCacheWrite, usageEmitted);
            }
        }

        // Stream EOF without [DONE] (some providers) — still finalize.
        if (!streamEnded) {
            flushToolCalls(queue, toolNames, toolArgs, toolIds);
            if (!usageEmitted[0] && (usageInput[0] > 0 || usageOutput[0] > 0)) {
                queue.put(new StreamEvent.Usage(usageInput[0], usageOutput[0],
                        usageCacheRead[0], usageCacheWrite[0]));
                usageEmitted[0] = true;
            }
            queue.put(new StreamEvent.StreamEnd("stop"));
        }
    }

    private boolean handleSseData(String data, BlockingQueue<StreamEvent> queue,
                                   Map<Integer, StringBuilder> toolNames,
                                   Map<Integer, StringBuilder> toolArgs,
                                   Map<Integer, String> toolIds,
                                   int[] usageInput, int[] usageOutput,
                                   int[] usageCacheRead, int[] usageCacheWrite,
                                   boolean[] usageEmitted)
            throws InterruptedException {
        JsonNode root;
        try { root = MAPPER.readTree(data); }
        catch (Exception e) { return false; }

        if (root.has("error")) {
            JsonNode err = root.get("error");
            String msg = err.has("message") ? err.get("message").asText() : err.toString();
            queue.put(new StreamEvent.Error(msg));
            return false;
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return false;

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        // text content
        if (delta.has("content") && !delta.get("content").isNull()) {
            String text = delta.get("content").asText();
            if (!text.isEmpty()) queue.put(new StreamEvent.TextDelta(text));
        }

        // reasoning_content (DeepSeek) → discard as thinking
        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
            String rc = delta.get("reasoning_content").asText();
            if (!rc.isEmpty()) queue.put(new StreamEvent.ThinkingDelta(rc));
        }

        // usage (DeepSeek / OpenAI compatible — appears in last chunk)
        if (root.has("usage") && !root.get("usage").isNull()) {
            JsonNode usage = root.get("usage");
            if (usage.has("prompt_tokens"))
                usageInput[0] = usage.get("prompt_tokens").asInt();
            if (usage.has("completion_tokens"))
                usageOutput[0] = usage.get("completion_tokens").asInt();

            // Cache breakdown — two provider formats:
            //   OpenAI:   prompt_tokens_details.cached_tokens
            //   DeepSeek: top-level prompt_cache_hit_tokens / prompt_cache_miss_tokens
            //             (hit + miss == prompt_tokens)
            JsonNode details = usage.path("prompt_tokens_details");
            if (details.has("cached_tokens")) {
                usageCacheRead[0] = details.get("cached_tokens").asInt();
            } else if (usage.has("prompt_cache_hit_tokens")) {
                usageCacheRead[0] = usage.get("prompt_cache_hit_tokens").asInt();
                usageCacheWrite[0] = usage.has("prompt_cache_miss_tokens")
                        ? usage.get("prompt_cache_miss_tokens").asInt() : 0;
            }

            if (!usageEmitted[0] && (usageInput[0] > 0 || usageOutput[0] > 0)) {
                queue.put(new StreamEvent.Usage(usageInput[0], usageOutput[0],
                        usageCacheRead[0], usageCacheWrite[0]));
                usageEmitted[0] = true;
            }
        }

        // tool_calls
        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
            for (JsonNode tc : delta.get("tool_calls")) {
                int idx = tc.path("index").asInt(0);
                if (tc.has("id") && !tc.get("id").isNull())
                    toolIds.put(idx, tc.get("id").asText());
                JsonNode fn = tc.path("function");
                if (fn.has("name") && !fn.get("name").isNull()) {
                    String name = fn.get("name").asText();
                    toolNames.computeIfAbsent(idx, k -> new StringBuilder()).append(name);
                    String callId = toolIds.getOrDefault(idx, "call_" + idx);
                    queue.put(new StreamEvent.ToolCallStart(callId, name));
                }
                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                    String argChunk = fn.get("arguments").asText();
                    toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argChunk);
                    queue.put(new StreamEvent.ToolCallDelta(argChunk));
                }
            }
        }

        // finish_reason
        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                ? choice.get("finish_reason").asText() : null;

        if ("tool_calls".equals(finishReason)) {
            flushToolCalls(queue, toolNames, toolArgs, toolIds);
            return false;
        }
        if ("stop".equals(finishReason) || "length".equals(finishReason)) {
            queue.put(new StreamEvent.StreamEnd("stop".equals(finishReason) ? "end_turn" : "max_tokens"));
            return true;
        }
        return false;
    }

    private void flushToolCalls(BlockingQueue<StreamEvent> queue,
                                 Map<Integer, StringBuilder> toolNames,
                                 Map<Integer, StringBuilder> toolArgs,
                                 Map<Integer, String> toolIds) throws InterruptedException {
        if (toolNames.isEmpty()) return;
        var sorted = new ArrayList<>(toolNames.keySet());
        Collections.sort(sorted);
        for (int idx : sorted) {
            String name = toolNames.get(idx).toString();
            String callId = toolIds.getOrDefault(idx, "call_" + idx);
            String rawArgs = toolArgs.containsKey(idx) ? toolArgs.get(idx).toString() : "{}";
            Map<String, Object> args;
            try { args = MAPPER.readValue(rawArgs, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
            catch (Exception e) { args = Map.of(); }
            queue.put(new StreamEvent.ToolCallComplete(callId, name, args));
        }
        toolNames.clear(); toolArgs.clear(); toolIds.clear();
    }

    private String buildRequestBody(List<ChatMessage> history, String prompt,
                                      List<Map<String, Object>> toolsList) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("max_tokens", MAX_TOKENS);

        ArrayNode msgs = MAPPER.createArrayNode();
        if (prompt != null && !prompt.isBlank()) {
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", prompt);
            msgs.add(sys);
        }
        for (var msg : history) {
            ObjectNode node = MAPPER.createObjectNode();
            switch (msg.getRole()) {
                case USER -> node.put("role", "user");
                case ASSISTANT -> {
                    node.put("role", "assistant");
                    if (msg.hasToolCalls()) {
                        // Build tool_calls array for proper Function Calling history
                        var tcArray = MAPPER.createArrayNode();
                        for (var tc : msg.getToolCalls()) {
                            var tcNode = MAPPER.createObjectNode();
                            tcNode.put("id", tc.id());
                            tcNode.put("type", "function");
                            var fnNode = MAPPER.createObjectNode();
                            fnNode.put("name", tc.name());
                            fnNode.put("arguments", MAPPER.writeValueAsString(tc.arguments()));
                            tcNode.set("function", fnNode);
                            tcArray.add(tcNode);
                        }
                        node.set("tool_calls", tcArray);
                    }
                    node.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
                case TOOL -> {
                    node.put("role", "tool");
                    node.put("tool_call_id", msg.getToolCallId());
                }
                default -> node.put("role", "user");
            }
            // Only set content for non-TOOL roles (TOOL messages have tool_call_id)
            if (msg.getRole() != ChatMessage.Role.TOOL) {
                node.put("content", msg.getContent() != null ? msg.getContent() : "");
            } else {
                node.put("content", msg.getContent() != null ? msg.getContent() : "");
            }
            msgs.add(node);
        }
        root.set("messages", msgs);

        if (toolsList != null && !toolsList.isEmpty()) {
            root.set("tools", MAPPER.valueToTree(toolsList));
        }

        return MAPPER.writeValueAsString(root);
    }
}
