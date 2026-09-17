package com.novacode.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and validates provider configuration from a YAML file.
 * Supports ${ENV_VAR} substitution in api_key values.
 */
public class ConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // config.yaml 现在除了 providers 还可能有 mcp_servers 等其它段，忽略未知字段
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static List<ProviderConfig> load(Path configPath) throws ConfigException {
        if (!Files.exists(configPath)) {
            throw new ConfigException(
                "Config file not found: " + configPath.toAbsolutePath() +
                "\nPlease create a config.yaml in the project root."
            );
        }

        ConfigFile configFile;
        try {
            configFile = MAPPER.readValue(configPath.toFile(), ConfigFile.class);
        } catch (IOException e) {
            throw new ConfigException("Failed to parse config file: " + e.getMessage());
        }

        if (configFile == null || configFile.providers == null || configFile.providers.isEmpty()) {
            throw new ConfigException("Config must contain a non-empty 'providers' list.");
        }

        return validate(configFile.providers);
    }

    /** 第 15 章：coordinator 能力开关是否开启（config.yaml 顶层 enable_coordinator_mode）。 */
    public static boolean coordinatorEnabled(Path configPath) {
        if (configPath == null || !Files.exists(configPath)) return false;
        try {
            ConfigFile configFile = MAPPER.readValue(configPath.toFile(), ConfigFile.class);
            return configFile != null && Boolean.TRUE.equals(configFile.enable_coordinator_mode);
        } catch (IOException e) {
            return false;
        }
    }

    private static List<ProviderConfig> validate(List<ProviderConfig> providers) throws ConfigException {
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig p = providers.get(i);
            String prefix = "providers[" + i + "]";

            if (p.getName() == null || p.getName().isBlank())
                throw new ConfigException(prefix + ": 'name' is required.");
            if (p.getProtocol() == null || p.getProtocol().isBlank())
                throw new ConfigException(prefix + ": 'protocol' is required.");
            String protocol = p.getProtocol().trim().toLowerCase();
            if (!protocol.equals("anthropic") && !protocol.equals("openai") && !protocol.equals("openai-compat"))
                throw new ConfigException(prefix + ": 'protocol' must be anthropic/openai/openai-compat, got '" + protocol + "'.");
            p.setProtocol(protocol);

            if (p.getApiKey() == null || p.getApiKey().isBlank())
                throw new ConfigException(prefix + ": 'api_key' is required.");
            String expandedKey = expandEnv(p.getApiKey());
            if (expandedKey.isBlank())
                throw new ConfigException(prefix + ": 'api_key' resolves to empty (missing env var?).");
            p.setApiKey(expandedKey);

            if (p.getModel() == null || p.getModel().isBlank())
                throw new ConfigException(prefix + ": 'model' is required.");

            // max_tokens 非法值回落默认（8192）
            if (p.getMaxTokens() <= 0) p.setMaxTokens(8192);

            if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
                p.setBaseUrl(switch (protocol) {
                    case "anthropic" -> "https://api.anthropic.com";
                    case "openai", "openai-compat" -> "https://api.openai.com";
                    default -> throw new ConfigException(prefix + ": unknown protocol '" + protocol + "'");
                });
            }
        }
        return providers;
    }

    static String expandEnv(String value) {
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            m.appendReplacement(sb, Matcher.quoteReplacement(envValue != null ? envValue : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static class ConfigFile {
        public List<ProviderConfig> providers;
        /** 第 15 章 F7：coordinator 能力开关（还需环境变量 NOVACODE_COORDINATOR=1/true 双锁生效）。 */
        public Boolean enable_coordinator_mode;
    }

    public static class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;
        public ConfigException(String message) { super(message); }
    }
}
