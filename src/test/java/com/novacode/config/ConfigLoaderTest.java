package com.novacode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 配置加载：max_tokens 校验、协议校验、环境变量展开。 */
class ConfigLoaderTest {

    @TempDir
    Path dir;

    private Path write(String yaml) throws Exception {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, yaml);
        return f;
    }

    private static final String BASE = """
            providers:
              - name: "T"
                protocol: openai-compat
                api_key: "sk-test"
                model: "m"
                base_url: "https://example.com"
            """;

    @Test
    void maxTokensDefaultsTo8192() throws Exception {
        assertEquals(8192, ConfigLoader.load(write(BASE)).get(0).getMaxTokens());
    }

    @Test
    void maxTokensExplicitValueHonored() throws Exception {
        assertEquals(16384, ConfigLoader.load(write(BASE + "    max_tokens: 16384\n")).get(0).getMaxTokens());
    }

    @Test
    void maxTokensInvalidFallsBackToDefault() throws Exception {
        assertEquals(8192, ConfigLoader.load(write(BASE + "    max_tokens: -5\n")).get(0).getMaxTokens());
    }

    @Test
    void contextWindowDefaultAndOverride() throws Exception {
        assertEquals(64_000, ConfigLoader.load(write(BASE)).get(0).getContextWindow());
        assertEquals(128_000, ConfigLoader.load(write(BASE + "    context_window: 128000\n")).get(0).getContextWindow());
    }

    @Test
    void unknownProtocolRejected() throws Exception {
        assertThrows(ConfigLoader.ConfigException.class,
                () -> ConfigLoader.load(write(BASE.replace("openai-compat", "grpc"))));
    }

    @Test
    void missingApiKeyRejected() throws Exception {
        assertThrows(ConfigLoader.ConfigException.class,
                () -> ConfigLoader.load(write(BASE.replace("api_key: \"sk-test\"", "api_key: \"\""))));
    }

    @Test
    void envExpansionResolvesMissingVarToEmptyThenRejected() throws Exception {
        Path f = write(BASE.replace("sk-test", "${NO_SUCH_ENV_VAR_XYZ_42}"));
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(f),
                "缺失环境变量展开为空 → 校验应拒绝");
    }

    @Test
    void expandEnvSubstitutesMissingVarToEmpty() {
        assertEquals("plain", ConfigLoader.expandEnv("plain"), "无占位符应原样返回");
        assertEquals("ac", ConfigLoader.expandEnv("a${NO_SUCH_ENV_VAR_XYZ_42}c"),
                "缺失环境变量应展开为空串");
    }

    @Test
    void emptyProvidersRejected() throws Exception {
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(write("providers: []\n")));
    }

    @Test
    void missingConfigFileRejected() {
        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(dir.resolve("nope.yaml")));
    }
}
