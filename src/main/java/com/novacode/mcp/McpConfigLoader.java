package com.novacode.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取并合并两级 MCP 配置（第 7 章）。
 *
 * <p>用户级：{@code <user.home>/.novacode/config.yaml}；项目级：{@code <user.dir>/config.yaml}。
 * 两级都使用相同的 {@code mcp_servers} map 形状（key = Server 名），项目级覆盖用户级同名 Server。
 * 任一文件缺失都跳过，不报错 —— MCP 完全可选（N3）。</p>
 */
public class McpConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // config.yaml 里除了 mcp_servers 还有 providers 等其它段，必须忽略未知字段
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static List<McpServerConfig> load() {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        Path user = Path.of(System.getProperty("user.home"), ".novacode", "config.yaml");
        Path project = Path.of(System.getProperty("user.dir"), "config.yaml");

        mergeFrom(merged, user);
        mergeFrom(merged, project);

        List<McpServerConfig> result = new ArrayList<>();
        for (var entry : merged.entrySet()) {
            McpServerConfig cfg = entry.getValue();
            cfg.name = entry.getKey();
            cfg.env = expandMap(cfg.env);
            cfg.headers = expandMap(cfg.headers);
            result.add(cfg);
        }
        return result;
    }

    /** 读取单个文件，把其中的 {@code mcp_servers} 并入 {@code merged}（后读覆盖同名）。 */
    private static void mergeFrom(Map<String, McpServerConfig> merged, Path file) {
        if (!Files.exists(file)) return;
        McpConfigFile parsed;
        try {
            parsed = MAPPER.readValue(file.toFile(), McpConfigFile.class);
        } catch (IOException e) {
            System.err.println("[MCP] failed to parse " + file.toAbsolutePath() + ": " + e.getMessage());
            return;
        }
        if (parsed == null || parsed.mcpServers == null) return;
        merged.putAll(parsed.mcpServers);
    }

    /** 对 map 里每个值做 {@code ${VAR}} 展开；变量缺失时保留原样（比清空更可调试）。 */
    private static Map<String, String> expandMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return map;
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            out.put(e.getKey(), expandEnv(e.getValue()));
        }
        return out;
    }

    static String expandEnv(String value) {
        if (value == null || value.indexOf("${") < 0) return value;
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            m.appendReplacement(sb, Matcher.quoteReplacement(envValue != null ? envValue : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 配置文件的顶层形状：只关心 {@code mcp_servers} 段，其余字段忽略。 */
    public static class McpConfigFile {
        public Map<String, McpServerConfig> mcpServers;
    }
}
