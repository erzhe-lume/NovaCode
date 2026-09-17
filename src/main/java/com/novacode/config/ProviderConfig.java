package com.novacode.config;

/**
 * Holds configuration for a single LLM provider.
 */
public class ProviderConfig {
    private String name;
    private String protocol;
    private String apiKey;
    private String model;
    private String baseUrl;
    private boolean thinking;
    /** 上下文窗口 Token 上限（第 8 章）。默认保守值，可经 config.yaml 的 context_window 覆盖。 */
    private int contextWindow = 64000;
    /** 单次响应输出 Token 上限。默认 8192 —— 大文件 WriteFile/EditFile 的 tool call JSON
     *  需要足够的输出空间，4096 容易在调用中途截断导致参数解析失败。 */
    private int maxTokens = 8192;

    public ProviderConfig() {}

    public ProviderConfig(String name, String protocol, String apiKey, String model,
                          String baseUrl, boolean thinking) {
        this.name = name;
        this.protocol = protocol;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.thinking = thinking;
    }

    // --- Getters ---
    public String getName() { return name; }
    public String getProtocol() { return protocol; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public boolean isThinking() { return thinking; }
    public int getContextWindow() { return contextWindow; }
    public int getMaxTokens() { return maxTokens; }

    // --- Setters (needed by SnakeYAML) ---
    public void setName(String name) { this.name = name; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setModel(String model) { this.model = model; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
