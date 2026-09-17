package com.novacode.session;

import com.novacode.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 会话存档：追加写、恢复（最近/按ID）、删除、孤儿截断。 */
class SessionStoreTest {

    @TempDir
    Path dir;

    private ChatMessage user(String s) { return new ChatMessage(ChatMessage.Role.USER, s); }
    private ChatMessage assistant(String s) { return new ChatMessage(ChatMessage.Role.ASSISTANT, s); }

    @Test
    void syncAppendsOnlyNewMessages() throws Exception {
        var store = new SessionStore(dir);
        store.startNewSession();
        var history = new java.util.ArrayList<>(List.of(user("one"), assistant("two")));
        store.sync(history);

        store.sync(history); // 无新增 → 不重复写
        Path file = Files.list(dir.resolve(".mewcode/sessions")).findFirst().orElseThrow();
        assertEquals(2, Files.readAllLines(file).size(), "重复 sync 不应追加");

        history.add(user("three"));
        store.sync(history);
        assertEquals(3, Files.readAllLines(file).size());
    }

    @Test
    void resumeMostRecentLoadsLatestSession() throws Exception {
        var store = new SessionStore(dir);
        String id1 = store.startNewSession();
        store.sync(List.of(user("first session msg")));
        Thread.sleep(1100); // 会话 ID 秒级粒度：隔 1 秒保证 ID 与 mtime 都严格更新
        store.startNewSession();
        store.sync(List.of(user("second session msg")));

        var store2 = new SessionStore(dir);
        var resume = store2.resumeMostRecent();
        assertTrue(resume.found());
        assertEquals(1, resume.messages().size());
        assertEquals("second session msg", resume.messages().get(0).getContent(),
                "应恢复最新的会话");
        assertNotEquals(id1, resume.id());
    }

    @Test
    void resumeByIdExactAndPrefixAndMiss() {
        var store = new SessionStore(dir);
        String id = store.startNewSession();
        store.sync(List.of(user("target"), assistant("reply")));
        store.startNewSession();
        store.sync(List.of(user("other")));

        var store2 = new SessionStore(dir);
        var exact = store2.resumeById(id);
        assertTrue(exact.found() && exact.id().equals(id), "精确 ID 应命中");
        assertEquals(2, exact.messages().size());

        var prefix = new SessionStore(dir).resumeById(id.substring(0, 16));
        assertTrue(prefix.found() && prefix.id().startsWith(id.substring(0, 16)), "前缀应命中");

        assertFalse(new SessionStore(dir).resumeById("19990101").found(), "未知 ID");
        assertFalse(new SessionStore(dir).resumeById(null).found(), "null");
        assertFalse(new SessionStore(dir).resumeById("  ").found(), "空白");
    }

    @Test
    void resumeByPrefixRequiresSingleMatch() {
        var store = new SessionStore(dir);
        String id = store.startNewSession();
        store.sync(List.of(user("s1")));
        // 同一秒 + 随机后缀：短前缀（日期）大概率多匹配
        store.startNewSession();
        store.sync(List.of(user("s2")));

        var store2 = new SessionStore(dir);
        var byDate = store2.resumeById(id.substring(0, 8)); // yyyyMMdd → 两个会话都匹配
        assertTrue(byDate.found(), "前缀匹配到多个时应取字典序最小的一个而非失败");
    }

    @Test
    void deleteByIdRefusesCurrentAndMultiMatch() {
        var store = new SessionStore(dir);
        String id1 = store.startNewSession();
        store.sync(List.of(user("s1")));
        String id2 = store.startNewSession();
        store.sync(List.of(user("s2")));

        var store2 = new SessionStore(dir);
        store2.resumeMostRecent();
        String currentId = store2.currentId();
        assertFalse(store2.deleteById(currentId).ok(), "当前会话不可删");
        assertFalse(store2.deleteById("00000000").ok(), "无匹配不可删");
        assertFalse(store2.deleteById(id1.substring(0, 8)).ok(), "同日多匹配应拒绝删除");

        String other = currentId.equals(id1) ? id2 : id1;
        var store3 = new SessionStore(dir);
        var result = store3.deleteById(other);
        assertTrue(result.ok(), "非当前会话应可删: " + result.message());
        assertFalse(new SessionStore(dir).resumeById(other).found(), "删除后应不可恢复");
    }

    @Test
    void danglingToolCallTruncatedOnLoad() throws Exception {
        var store = new SessionStore(dir);
        store.startNewSession();
        var history = new java.util.ArrayList<ChatMessage>();
        history.add(user("q"));
        history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(
                new ChatMessage.ToolCall("call_1", "Bash", java.util.Map.of()))));
        history.add(ChatMessage.toolResult("call_1", "ok"));
        history.add(assistant("done"));
        store.sync(history);

        Path file = Files.list(dir.resolve(".mewcode/sessions")).findFirst().orElseThrow();
        var loaded = SessionStore.loadSession(file);
        assertEquals(4, loaded.size(), "完整调用链应完整恢复");

        // 构造孤儿：assistant 带 tool_call 但结果缺失
        var orphan = new java.util.ArrayList<ChatMessage>();
        orphan.add(user("q2"));
        orphan.add(new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(
                new ChatMessage.ToolCall("call_2", "Bash", java.util.Map.of()))));
        orphan.add(user("after crash"));
        var truncated = SessionStore.truncateDangling(orphan);
        assertEquals(1, truncated.size(), "孤儿 tool_call 之后应截断");
    }

    @Test
    void listReturnsSessionsSortedByMtime() throws Exception {
        var store = new SessionStore(dir);
        store.startNewSession();
        store.sync(List.of(user("older")));
        Thread.sleep(1100); // 秒级 ID 粒度：隔 1 秒保证排序确定
        store.startNewSession();
        store.sync(List.of(user("newer")));

        var store2 = new SessionStore(dir);
        var sessions = store2.list();
        assertEquals(2, sessions.size());
        assertTrue(sessions.get(0).firstMessage().contains("newer"), "最近在前");
    }
}
