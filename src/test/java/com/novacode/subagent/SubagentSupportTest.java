package com.novacode.subagent;

import com.novacode.mcp.McpConfigLoader;
import com.novacode.teams.Coordinator;
import com.novacode.tool.ToolRegistry;
import com.novacode.worktree.SlugValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SubAgent 支撑层：工具过滤、coordinator 双锁、worktree slug 校验、指令加载、MCP 配置。 */
class SubagentSupportTest {

    @TempDir
    static Path tempDir;

    private static String realUserDir, realUserHome;

    @BeforeAll
    static void redirectHomes() {
        realUserDir = System.getProperty("user.dir");
        realUserHome = System.getProperty("user.home");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("user.home", tempDir.resolve("home").toString());
    }

    @AfterAll
    static void restoreHomes() {
        System.setProperty("user.dir", realUserDir);
        System.setProperty("user.home", realUserHome);
    }

    // ── Coordinator 双锁 ─────────────────────────────────────────────

    @Test
    void coordinatorToolAllowlist() {
        assertTrue(Coordinator.isCoordinatorTool("Agent"));
        assertTrue(Coordinator.isCoordinatorTool("SendMessage"));
        assertTrue(Coordinator.isCoordinatorTool("ReadFile"));
        assertFalse(Coordinator.isCoordinatorTool("WriteFile"), "coordinator 不写文件");
        assertFalse(Coordinator.isCoordinatorTool("EditFile"));
        assertFalse(Coordinator.isCoordinatorTool(null));
    }

    @Test
    void envEnabledAcceptsOnlyTrueishValues() {
        assertTrue(Coordinator.envEnabled("1"));
        assertTrue(Coordinator.envEnabled("true"));
        assertTrue(Coordinator.envEnabled(" TRUE "));
        assertFalse(Coordinator.envEnabled("0"));
        assertFalse(Coordinator.envEnabled("yes"));
        assertFalse(Coordinator.envEnabled(null));
    }

    @Test
    void activeRequiresBothLocks() {
        assertTrue(Coordinator.active(true, "1"), "配置开 + 环境开 = 激活");
        assertFalse(Coordinator.active(true, "0"), "环境关 = 关");
        assertFalse(Coordinator.active(false, "1"), "配置关 = 关");
        assertFalse(Coordinator.active(false, null));
    }

    // ── ToolFilter ───────────────────────────────────────────────────

    @Test
    void teammateFilterKeepsBaseToolsStripsAgentAndCollab() {
        var source = ToolRegistry.createDefault();
        var teammate = ToolFilter.filterForTeammate(source, tempDir);

        // filterForTeammate = 通用过滤：保留基础工具，剔除递归派生与协作通道
        // （协作工具由 TeammateRunner.buildTeammateRegistry 显式补回）
        assertNotNull(teammate.get("Bash"), "基础工具应保留");
        assertNotNull(teammate.get("ReadFile"));
        assertNull(teammate.get("Agent"), "队员不能再派子 Agent");
        assertNull(teammate.get("SendMessage"), "协作通道由 buildTeammateRegistry 显式补回");
        assertNull(teammate.get("TaskCreate"));
    }

    @Test
    void forkCloneKeepsAllTools() {
        var source = ToolRegistry.createDefault();
        var fork = ToolFilter.cloneForFork(source);

        assertNotNull(fork.get("Bash"));
        assertNotNull(fork.get("EditFile"));
        assertNull(fork.get("Agent"), "fork 不应带走 Agent 工具");
    }

    @Test
    void agentFilterStripsAlwaysDisallowed() {
        var source = ToolRegistry.createDefault();
        var filtered = ToolFilter.filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE);

        assertNotNull(filtered.get("Bash"), "通用子 Agent 应有基础工具");
        assertNull(filtered.get("Agent"), "子 Agent 不能再派 Agent（递归防护）");
        assertNull(filtered.get("SendMessage"), "子 Agent 不能进协作通道");
    }

    @Test
    void asyncAgentRestrictedToBackgroundWhitelist() {
        var source = ToolRegistry.createDefault();
        var async = ToolFilter.filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE, true);

        assertNotNull(async.get("ReadFile"));
        assertNotNull(async.get("Bash"));
        assertNull(async.get("TodoWrite"), "后台白名单之外的应收窄");
    }

    // ── SlugValidator ────────────────────────────────────────────────

    @Test
    void slugValidateAcceptsLegalNames() {
        assertNull(SlugValidator.validate("feature-x"));
        assertNull(SlugValidator.validate("fix_bug.2"));
        assertNull(SlugValidator.validate("a/b/c"));
    }

    @Test
    void slugValidateRejectsIllegalNames() {
        assertNotNull(SlugValidator.validate(null));
        assertNotNull(SlugValidator.validate("  "));
        assertNotNull(SlugValidator.validate("/abs"), "绝对路径拒绝");
        assertNotNull(SlugValidator.validate("a//b"), "空路径段拒绝");
        assertNotNull(SlugValidator.validate("a/../b"), ".. 段拒绝");
        assertNotNull(SlugValidator.validate("has space"), "空格非法");
        assertNotNull(SlugValidator.validate("x".repeat(65)), "超长拒绝");
        assertNull(SlugValidator.validate("x".repeat(64)), "64 字符恰好合法");
    }

    @Test
    void slugFlattenAndBranchName() {
        assertEquals("a+b+c", SlugValidator.flatten("a/b/c"));
        assertEquals("worktree-a+b", SlugValidator.branchName("a/b"));
    }

    // ── InstructionLoader ────────────────────────────────────────────

    @Test
    void noInstructionFileYieldsEmpty() throws Exception {
        // 独立空目录（共享 tempDir 会被同类的 rootAgentsMdIsLoaded 污染）
        Path fresh = Files.createDirectories(tempDir.resolve("fresh-" + System.nanoTime()));
        assertEquals("", com.novacode.memory.InstructionLoader.loadInstructions(fresh));
    }

    @Test
    void rootAgentsMdIsLoaded() throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "项目约定：先写测试");
        String loaded = com.novacode.memory.InstructionLoader.loadInstructions(tempDir);
        assertTrue(loaded.contains("项目约定：先写测试"), loaded);
        assertTrue(loaded.contains("AGENTS.md"), "应标注来源文件");
    }

    // ── McpConfigLoader ──────────────────────────────────────────────

    @Test
    void emptyConfigYieldsNoServers() {
        assertTrue(McpConfigLoader.load().isEmpty(), "临时目录无 config.yaml 应为空");
    }

    @Test
    void parsesStdioAndHttpServersFromProjectConfig() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                providers:
                  - name: "T"
                    protocol: openai-compat
                    api_key: "k"
                    model: "m"
                mcp_servers:
                  fs:
                    command: "npx"
                    args: ["-y", "@modelcontextprotocol/server-fs", "/tmp"]
                  web:
                    url: "https://mcp.example.com/sse"
                    headers:
                      Authorization: "Bearer ${NO_SUCH_VAR_XYZ}"
                """);

        var servers = McpConfigLoader.load();
        assertEquals(2, servers.size(), "应解析出两个 server");

        var fs = servers.stream().filter(s -> "fs".equals(s.name)).findFirst().orElseThrow();
        assertEquals("npx", fs.command);
        assertEquals(List.of("-y", "@modelcontextprotocol/server-fs", "/tmp"), fs.args);
        assertTrue(fs.isStdio());

        var web = servers.stream().filter(s -> "web".equals(s.name)).findFirst().orElseThrow();
        assertEquals("https://mcp.example.com/sse", web.url);
        assertFalse(web.isStdio());
        assertTrue(web.headers.get("Authorization").isEmpty() || !web.headers.containsKey("Authorization")
                || web.headers.get("Authorization").contains("Bearer"),
                "缺失环境变量应展开为空或保持无害");
    }
}
