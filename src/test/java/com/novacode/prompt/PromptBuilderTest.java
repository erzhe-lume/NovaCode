package com.novacode.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PromptBuilder：稳定前缀字节一致性（缓存根基）、环境块、可选槽。 */
class PromptBuilderTest {

    @Test
    void stableModulesAreByteIdenticalAcrossCalls() {
        String a = PromptBuilder.buildStableModules();
        String b = PromptBuilder.buildStableModules();
        assertFalse(a.isBlank(), "稳定模块不应为空");
        assertEquals(a, b, "稳定前缀必须字节一致，否则 prompt caching 每轮失效");
    }

    @Test
    void stablePrefixIsContainedInFullPrompt() {
        var env = new PromptBuilder.EnvironmentContext(
                "/tmp/proj", "windows", "amd64", "bash", true, "main", "1.0.0", "m", "2026-09-16");
        String full = PromptBuilder.buildSystemPrompt(env,
                new PromptBuilder.BuildOptions(null, null, null));
        assertTrue(full.startsWith(PromptBuilder.buildStableModules()),
                "完整 system prompt 必须以稳定前缀开头（前缀缓存命中条件）");
    }

    @Test
    void environmentSectionAppearsAfterStableModules() {
        var env = new PromptBuilder.EnvironmentContext(
                "D:/work", "linux", "x64", "zsh", false, "", "1.0.0", "deepseek-flash", "2026-09-16");
        String full = PromptBuilder.buildSystemPrompt(env, new PromptBuilder.BuildOptions(null, null, null));
        assertTrue(full.contains("D:/work"), "环境块应含工作目录");
        assertTrue(full.contains("deepseek-flash"), "环境块应含模型名");
    }

    @Test
    void optionalSlotsOnlyWhenNonEmpty() {
        var env = new PromptBuilder.EnvironmentContext(
                "/p", "linux", "x64", "sh", false, "", "1.0.0", "m", "2026-09-16");
        String without = PromptBuilder.buildSystemPrompt(env, new PromptBuilder.BuildOptions(null, null, null));
        String with = PromptBuilder.buildSystemPrompt(env,
                new PromptBuilder.BuildOptions("技能菜单", "自定义指令", "记忆索引"));

        assertFalse(without.contains("记忆索引"));
        assertTrue(with.contains("技能菜单"));
        assertTrue(with.contains("自定义指令"));
        assertTrue(with.contains("记忆索引"));
        assertTrue(with.startsWith(without.substring(0, Math.min(without.length(), 100))),
                "可选槽追加不应破坏前缀开头");
    }
}
