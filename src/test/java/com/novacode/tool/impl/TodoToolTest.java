package com.novacode.tool.impl;

import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** TodoWrite：状态机、唯一 in_progress 约束、持久化与恢复。 */
class TodoToolTest {

    @TempDir
    Path dir;

    private Path store() {
        return dir.resolve(".novacode").resolve("todos.json");
    }

    @Test
    void validListRendersWithMarks() {
        var todo = new TodoTool(store());
        ToolResult result = todo.execute(Map.of("todos", List.of(
                Map.of("content", "读取配置", "status", "completed"),
                Map.of("content", "修改循环", "status", "in_progress"),
                Map.of("content", "补测试", "status", "pending"))));

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("[x] 读取配置"), result.output());
        assertTrue(result.output().contains("[>] 修改循环"), result.output());
        assertTrue(result.output().contains("[ ] 补测试"), result.output());
        assertTrue(result.output().contains("(1/3 completed)"), result.output());
    }

    @Test
    void twoInProgressRejected() {
        var todo = new TodoTool(store());
        ToolResult result = todo.execute(Map.of("todos", List.of(
                Map.of("content", "a", "status", "in_progress"),
                Map.of("content", "b", "status", "in_progress"))));
        assertTrue(result.isError());
    }

    @Test
    void invalidStatusRejected() {
        var todo = new TodoTool(store());
        ToolResult result = todo.execute(Map.of("todos",
                List.of(Map.of("content", "x", "status", "done"))));
        assertTrue(result.isError());
        assertTrue(result.output().contains("pending"));
    }

    @Test
    void emptyContentRejected() {
        var todo = new TodoTool(store());
        ToolResult result = todo.execute(Map.of("todos",
                List.of(Map.of("content", "  ", "status", "pending"))));
        assertTrue(result.isError());
    }

    @Test
    void nonArrayTodosRejected() {
        var todo = new TodoTool(store());
        ToolResult result = todo.execute(Map.of("todos", "not-a-list"));
        assertTrue(result.isError());
    }

    @Test
    void persistsAndRestoresAcrossInstances() {
        var first = new TodoTool(store());
        first.execute(Map.of("todos", List.of(
                Map.of("content", "持久化任务", "status", "in_progress"))));
        assertTrue(Files.exists(store()), "清单应落盘");

        // 新实例从磁盘恢复（不经内存）
        var second = new TodoTool(store());
        assertEquals(1, second.snapshot().size(), "新实例应从磁盘恢复清单");
        assertEquals("持久化任务", second.snapshot().get(0).get("content"));

        ToolResult result = second.execute(Map.of("todos", List.of(
                Map.of("content", "重写", "status", "pending"))));
        assertFalse(result.isError());
        assertTrue(result.output().contains("(0/1 completed)"), result.output());
    }

    @Test
    void clearEmptiesList() {
        var todo = new TodoTool(store());
        todo.execute(Map.of("todos", List.of(Map.of("content", "a", "status", "pending"))));
        ToolResult result = todo.execute(Map.of("todos", List.of()));
        assertTrue(result.output().contains("cleared"), result.output());
    }

    @Test
    void categoryIsInternal() {
        assertEquals(ToolCategory.INTERNAL, new TodoTool(store()).category());
    }
}
