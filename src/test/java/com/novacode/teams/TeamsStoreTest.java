package com.novacode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 团队协作层：文件邮箱（投递/未读协议）与共享任务板（CRUD/状态/持久化）。 */
class TeamsStoreTest {

    @TempDir
    Path dir;

    // ── FileMailBox ──────────────────────────────────────────────────

    @Test
    void sendAndReadUnreadProtocol() {
        var box = new FileMailBox(dir.resolve("inboxes"));
        box.send("lead", new MailMessage("alice", "任务A完成"));
        box.send("lead", new MailMessage("bob", "需要审批"));

        List<MailMessage> unread = box.readUnread("lead");
        assertEquals(2, unread.size(), "两条都应未读");
        assertEquals("alice", unread.get(0).from());
        assertEquals("任务A完成", unread.get(0).text());

        box.markAllRead("lead");
        assertTrue(box.readUnread("lead").isEmpty(), "标记后未读应为空");
        assertEquals(2, box.list("lead").size(), "全部消息仍应保留");
        assertTrue(box.list("lead").get(0).read());
    }

    @Test
    void emptyMailboxIsGraceful() {
        var box = new FileMailBox(dir.resolve("inboxes"));
        assertTrue(box.readUnread("nobody").isEmpty(), "无邮箱文件应返回空而非异常");
        assertTrue(box.list("nobody").isEmpty());
    }

    @Test
    void independentInboxesDoNotCrossContaminate() {
        var box = new FileMailBox(dir.resolve("inboxes"));
        box.send("alice", new MailMessage("lead", "给alice"));
        box.send("bob", new MailMessage("lead", "给bob"));

        assertEquals(1, box.readUnread("alice").size());
        assertEquals("给alice", box.readUnread("alice").get(0).text());
        assertEquals(1, box.readUnread("bob").size());
    }

    @Test
    void stateSurvivesNewInstanceOnDisk() {
        var box1 = new FileMailBox(dir.resolve("inboxes"));
        box1.send("lead", new MailMessage("alice", "持久化消息"));

        var box2 = new FileMailBox(dir.resolve("inboxes"));
        List<MailMessage> msgs = box2.list("lead");
        assertEquals(1, msgs.size(), "新实例应从磁盘恢复");
        assertEquals("持久化消息", msgs.get(0).text());
        assertFalse(msgs.get(0).read());
    }

    @Test
    void corruptedMailboxDegradesToEmpty() throws Exception {
        Path inboxes = dir.resolve("inboxes-bad");
        Files.createDirectories(inboxes);
        Files.writeString(inboxes.resolve("lead.json"), "not-json{{");
        var box = new FileMailBox(inboxes);
        assertTrue(box.list("lead").isEmpty(), "损坏邮箱应容错为空");
    }

    // ── SharedTaskStore ──────────────────────────────────────────────

    @Test
    void createAssignsIncrementingIdsAndPersists() {
        var store = new SharedTaskStore(dir.resolve("tasks.json"));
        var t1 = store.create("搭建骨架", "初始化项目", "alice", null, null, "lead");
        var t2 = store.create("写测试", null, "bob", null, null, "lead");

        assertEquals("task-1", t1.id());
        assertEquals("task-2", t2.id());
        assertEquals("pending", t1.status());

        // 新实例从磁盘恢复
        var reloaded = new SharedTaskStore(dir.resolve("tasks.json"));
        assertEquals(2, reloaded.listTasks(null, null).size());
        assertEquals("task-3", reloaded.create("第三个", null, null, null, null, "lead").id(),
                "next_id 应跨实例持久");
    }

    @Test
    void updateChangesStatusAndAssignee() {
        var store = new SharedTaskStore(dir.resolve("tasks.json"));
        var t = store.create("任务", null, null, null, null, "lead");

        var updated = store.update(t.id(), "in_progress", "alice", "新描述", null, null);
        assertEquals("in_progress", updated.status());
        assertEquals("alice", updated.assignee());
        assertEquals("新描述", updated.description());

        var done = store.update(t.id(), "completed", null, null, null, null);
        assertEquals("completed", done.status());
        assertEquals("alice", done.assignee(), "null assignee 保持不变");
    }

    @Test
    void updateInvalidStatusThrows() {
        var store = new SharedTaskStore(dir.resolve("tasks.json"));
        var t = store.create("任务", null, null, null, null, "lead");
        assertThrows(IllegalArgumentException.class,
                () -> store.update(t.id(), "done", null, null, null, null));
    }

    @Test
    void updateUnknownTaskReturnsNull() {
        var store = new SharedTaskStore(dir.resolve("tasks.json"));
        assertNull(store.update("task-99", "pending", null, null, null, null));
    }

    @Test
    void dependencyListsAppendDeduplicated() {
        var store = new SharedTaskStore(dir.resolve("tasks.json"));
        var t = store.create("父任务", null, null, null, null, "lead");
        var other = store.create("子任务", null, null, null, null, "lead");

        store.update(other.id(), null, null, null, List.of(t.id(), t.id()), null);
        var after = store.get(other.id());
        assertEquals(List.of(t.id()), after.blocks(), "依赖追加应去重");

        store.update(other.id(), null, null, null, List.of(t.id()), null);
        assertEquals(List.of(t.id()), store.get(other.id()).blocks(), "重复添加不增长");
    }

    @Test
    void listFiltersByStatusAndAssignee() {
        var store = new SharedTaskStore(dir.resolve("tasks.json"));
        var a = store.create("A", null, "alice", null, null, "lead");
        var b = store.create("B", null, "bob", null, null, "lead");
        store.update(b.id(), "in_progress", null, null, null, null);

        assertEquals(2, store.listTasks(null, null).size());
        assertEquals(1, store.listTasks("pending", null).size());
        assertEquals("A", store.listTasks("pending", null).get(0).title());
        assertEquals(1, store.listTasks(null, "alice").size());
        assertEquals(0, store.listTasks("completed", null).size());
    }

    @Test
    void isValidStatusSemantics() {
        assertTrue(SharedTaskStore.isValidStatus("pending"));
        assertTrue(SharedTaskStore.isValidStatus("blocked"));
        assertFalse(SharedTaskStore.isValidStatus("done"));
        assertFalse(SharedTaskStore.isValidStatus(null));
    }

    @Test
    void missingFileDegradesToEmptyBoard() {
        var store = new SharedTaskStore(dir.resolve("nope/tasks.json"));
        assertTrue(store.listTasks(null, null).isEmpty());
        assertNull(store.get("task-1"));
        assertNotNull(store.create("首个", null, null, null, null, "lead"),
                "空板上创建应成功并落盘");
    }
}
