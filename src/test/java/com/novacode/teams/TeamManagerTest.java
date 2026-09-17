package com.novacode.teams;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** TeamManager：团队生命周期、成员花名册、磁盘重建、后端检测。 */
class TeamManagerTest {

    @TempDir
    static Path tempDir;

    private static String realUserHome;

    @BeforeAll
    static void redirectUserHome() {
        realUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterAll
    static void restoreUserHome() {
        System.setProperty("user.home", realUserHome);
        // 清理单例里本测试注册的名字，避免影响其他测试
        for (String n : new String[]{"worker-a", "worker-b"}) {
            AgentNameRegistry.getInstance().unregister(n);
        }
    }

    @Test
    void createTeamPersistsAndRejectsDuplicate() {
        var tm = new TeamManager();
        TeamManager.Team t = tm.createTeam("alpha", TeamManager.TeamMode.IN_PROCESS, null, "测试团队");

        assertEquals("alpha", t.name());
        assertEquals("lead", t.leadAgentId(), "leadAgentId 缺省为 lead");
        assertNotNull(t.mailBox());
        assertNotNull(t.taskStore());
        assertTrue(Files.exists(TeamManager.teamsBaseDir().resolve("alpha")),
                "团队目录应落盘");

        assertThrows(IllegalStateException.class,
                () -> tm.createTeam("alpha", TeamManager.TeamMode.IN_PROCESS, null, null),
                "同名团队应拒绝");
    }

    @Test
    void getTeamRebuildsFromDiskWhenNotInMemory() {
        var tm1 = new TeamManager();
        tm1.createTeam("beta", TeamManager.TeamMode.IN_PROCESS, null, "跨进程");
        TeamManager.Team withMeta = tm1.getTeam("beta");
        withMeta.addMember(new TeamManager.Member("worker-a"));
        tm1.setMemberMeta(withMeta, "worker-a", "general", "deepseek-flash", null, false);

        // 全新管理器：内存无 → 磁盘重建
        var tm2 = new TeamManager();
        TeamManager.Team rebuilt = tm2.getTeam("beta");
        assertNotNull(rebuilt, "磁盘重建应成功");
        assertTrue(rebuilt.hasMember("worker-a"), "成员花名册应从磁盘恢复");
        assertEquals("deepseek-flash", rebuilt.getMember("worker-a").model, "成员元信息应恢复");
    }

    @Test
    void getOrCreateIsIdempotent() {
        var tm = new TeamManager();
        TeamManager.Team a = tm.getOrCreateTeam("gamma", TeamManager.TeamMode.IN_PROCESS, null, null);
        TeamManager.Team b = tm.getOrCreateTeam("gamma", TeamManager.TeamMode.IN_PROCESS, null, null);
        assertSame(a, b, "已存在时不应重建");
    }

    @Test
    void deleteTeamRemovesDirectoryAndForgets() {
        var tm = new TeamManager();
        tm.createTeam("delta", TeamManager.TeamMode.IN_PROCESS, null, null);
        assertTrue(tm.deleteTeam("delta"));
        assertFalse(tm.hasTeam("delta"));
        assertFalse(Files.exists(TeamManager.teamsBaseDir().resolve("delta")), "目录应删除");
        assertFalse(tm.deleteTeam("delta"), "再删应 false");
    }

    @Test
    void backendDetectionPrefersTmuxThenIterm() {
        assertEquals(TeamManager.TeamMode.TMUX,
                TeamManager.detectBackendFromEnv("/tmp/tmux-1", ""));
        assertEquals(TeamManager.TeamMode.ITERM,
                TeamManager.detectBackendFromEnv("", "iterm-xyz"));
        assertEquals(TeamManager.TeamMode.IN_PROCESS,
                TeamManager.detectBackendFromEnv(null, null));
        assertEquals(TeamManager.TeamMode.TMUX,
                TeamManager.detectBackendFromEnv("set", "also-set"), "tmux 优先");
    }

    @Test
    void leadInboxIsRecipientNameWithFallback() {
        var tm = new TeamManager();
        TeamManager.Team t = tm.createTeam("epsilon", TeamManager.TeamMode.IN_PROCESS, null, null);
        assertEquals("lead", t.leadInbox(), "缺省收件人是 lead");

        // lead 收件箱经 FileMailBox 收发
        t.sendMessage(t.leadInbox(), new MailMessage("worker-a", "汇报内容"));
        var box = t.mailBox();
        assertEquals(1, box.readUnread(t.leadInbox()).size());
        assertEquals("汇报内容", box.readUnread(t.leadInbox()).get(0).text());
    }

    @Test
    void memberRequiresNonBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new TeamManager.Member(null));
        assertThrows(IllegalArgumentException.class, () -> new TeamManager.Member("  "));
    }
}
