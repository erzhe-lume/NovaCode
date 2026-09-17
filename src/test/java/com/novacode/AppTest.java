package com.novacode;

import com.novacode.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 启动参数 provider 选择。 */
class AppTest {

    private static List<ProviderConfig> providers() {
        var deepseek = new ProviderConfig("DeepSeek", "openai-compat", "k1", "deepseek-flash",
                "https://api.deepseek.com", false);
        var gpt = new ProviderConfig("GPT-4o", "openai", "k2", "gpt-4o",
                "https://api.openai.com", false);
        var anthropic = new ProviderConfig("Claude", "anthropic", "k3", "claude-x",
                "https://api.anthropic.com", false);
        return List.of(deepseek, gpt, anthropic);
    }

    @Test
    void defaultIsFirstProvider() {
        assertEquals("DeepSeek", App.selectProvider(providers(), new String[0]).getName());
        assertEquals("DeepSeek",
                App.selectProvider(providers(), new String[]{"config.yaml"}).getName());
    }

    @Test
    void selectByNameExactCaseInsensitive() {
        assertEquals("GPT-4o",
                App.selectProvider(providers(), new String[]{"--provider", "gpt-4o"}).getName());
        assertEquals("Claude",
                App.selectProvider(providers(), new String[]{"--provider", "CLAUDE"}).getName());
    }

    @Test
    void selectByNameUniquePrefix() {
        assertEquals("DeepSeek",
                App.selectProvider(providers(), new String[]{"--provider", "deep"}).getName());
    }

    @Test
    void selectByOneBasedIndex() {
        assertEquals("GPT-4o",
                App.selectProvider(providers(), new String[]{"--provider", "2"}).getName());
        assertEquals("Claude",
                App.selectProvider(providers(), new String[]{"--provider", "3"}).getName());
    }

    @Test
    void uniqueSingleLetterPrefixMatches() {
        assertEquals("GPT-4o",
                App.selectProvider(providers(), new String[]{"--provider", "g"}).getName());
        // 越界下标 / 未知名称会 System.exit(1) 列出可用项——不在单测中触发（会终止 JVM）
    }

    @Test
    void resolveProviderReturnsNullWhenNotFound() {
        assertNull(App.resolveProvider(providers(), "nope"), "未知名称 → null");
        assertNull(App.resolveProvider(providers(), "99"), "越界下标 → null");
        assertNull(App.resolveProvider(providers(), "0"), "0 不是合法的 1 基下标");
        // 前缀歧义（多个命中）→ null
        assertNull(App.resolveProvider(List.of(
                new ProviderConfig("aa1", "openai", "k", "m", "u", false),
                new ProviderConfig("aa2", "openai", "k", "m", "u", false)), "aa"));
    }
}
