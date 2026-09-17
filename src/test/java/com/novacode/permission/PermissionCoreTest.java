package com.novacode.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 权限安全核心：规则引擎三层语义、黑名单、路径沙箱。 */
class PermissionCoreTest {

    @TempDir
    Path dir;

    // ── RuleEngine 三层语义 ──────────────────────────────────────────

    @Test
    void withinTierDenyBeatsAllow() {
        var engine = new RuleEngine();
        engine.addRule(RuleTier.PROJECT, Rule.of("Write(src/**)", true, "proj"));
        engine.addRule(RuleTier.PROJECT, Rule.of("Write(src/secret*)", false, "proj"));

        Decision d = engine.match("Write", "src/secret.txt");
        assertEquals(Decision.Verdict.DENY, d.verdict(), "同层 deny 应压过 allow");
    }

    @Test
    void nearestTierWinsOverOuterDeny() {
        var engine = new RuleEngine();
        engine.addRule(RuleTier.LOCAL, Rule.of("Edit(src/**)", true, "local"));
        engine.addRule(RuleTier.PROJECT, Rule.of("Edit(src/**)", false, "proj"));

        Decision d = engine.match("Edit", "src/a.java");
        assertEquals(Decision.Verdict.ALLOW, d.verdict(), "最近层 LOCAL allow 应胜出");
    }

    @Test
    void nearestTierDenyShortCircuitsOuterAllow() {
        var engine = new RuleEngine();
        engine.addRule(RuleTier.LOCAL, Rule.of("Edit(src/secret*)", false, "local"));
        engine.addRule(RuleTier.PROJECT, Rule.of("Edit(src/**)", true, "proj"));

        assertEquals(Decision.Verdict.DENY,
                engine.match("Edit", "src/secret.txt").verdict(),
                "LOCAL deny 应短路 PROJECT allow");
    }

    @Test
    void noMatchingRuleReturnsNull() {
        var engine = new RuleEngine();
        engine.addRule(RuleTier.PROJECT, Rule.of("Bash(git *)", true, "proj"));
        assertNull(engine.match("Write", "src/a.java"), "工具名不匹配 → null");
        assertNull(engine.match("Bash", "npm install"), "subject 不匹配 → null");
    }

    @Test
    void noPatternMatchesEverySubject() {
        var engine = new RuleEngine();
        engine.addRule(RuleTier.USER, Rule.of("Read", true, "user"));
        assertNotNull(engine.match("Read", "anything/at/all"), "无括号规则应匹配该工具的一切调用");
    }

    @Test
    void friendlyNameGatesRule() {
        var engine = new RuleEngine();
        engine.addRule(RuleTier.PROJECT, Rule.of("Write(src/**)", false, "proj"));
        assertNull(engine.match("Edit", "src/a.java"), "friendlyName 不同不应命中");
    }

    @Test
    void isEmptyReflectsState() {
        var engine = new RuleEngine();
        assertTrue(engine.isEmpty());
        engine.addRule(RuleTier.USER, Rule.of("Read", true, "user"));
        assertFalse(engine.isEmpty());
    }

    // ── Blacklist ────────────────────────────────────────────────────

    @Test
    void blacklistCatchesDestructiveCommands() {
        var bl = new Blacklist();
        assertNotNull(bl.matches("rm -rf /"), "rm -rf / 应进黑名单");
        assertNotNull(bl.matches("del /f /s /q C:\\"), "Windows 递归删除应在黑名单");
        assertNull(bl.matches("ls -la"), "普通命令不应命中");
        assertNull(bl.matches("git status"), "git 查询不应命中");
    }

    // ── PathSandbox ──────────────────────────────────────────────────

    @Test
    void sandboxAllowsPathsInsideRoot() {
        var sandbox = new PathSandbox(dir);
        assertNull(sandbox.check(dir.resolve("src/A.java")), "根内路径应放行");
        assertNull(sandbox.check(dir.resolve("a/b/c.txt")), "深层路径应放行");
    }

    @Test
    void sandboxDeniesPathsOutsideRoot() {
        var sandbox = new PathSandbox(dir);
        assertNotNull(sandbox.check(dir.resolveSibling(dir.getFileName() + "-outside.txt")),
                "根外路径应报违规");
        assertNotNull(sandbox.check(Path.of("C:", "Windows", "System32", "x.txt")),
                "系统路径应报违规");
    }

    @Test
    void sandboxResolveMakesRelativeAbsolute() {
        var sandbox = new PathSandbox(dir);
        Path resolved = sandbox.resolve(Path.of("src", "A.java"));
        assertTrue(resolved.isAbsolute(), "相对路径应解析为绝对");
        assertTrue(resolved.startsWith(dir), "应基于沙箱根解析");
    }
}
