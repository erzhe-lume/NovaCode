package com.novacode.protocol;

import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/** Unified LLM client interface with static factory. */
public interface LlmClient {
    BlockingQueue<StreamEvent> stream(List<ChatMessage> history, String systemPrompt);
    default void setTools(List<Map<String, Object>> tools) {}

    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "openai", "openai-compat" -> new OpenAiCompatClient(cfg, systemPrompt);
            case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
            default -> throw new IllegalArgumentException("Unknown protocol: " + cfg.getProtocol());
        };
    }
}
