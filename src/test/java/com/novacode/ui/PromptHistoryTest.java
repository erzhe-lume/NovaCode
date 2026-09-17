package com.novacode.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 输入历史持久化：JSONL 往返、旧格式兼容、坏行容错、条数上限。 */
class PromptHistoryTest {

    @TempDir
    Path dir;

    @Test
    void appendThenLoadReturnsNewestFirst() throws Exception {
        Path f = dir.resolve("history.jsonl");
        ChatModel.appendPromptHistory(f, "第一条");
        ChatModel.appendPromptHistory(f, "第二条");
        ChatModel.appendPromptHistory(f, "第三条");

        List<String> loaded = ChatModel.loadPromptHistory(f);
        assertEquals(List.of("第三条", "第二条", "第一条"), loaded, "最新在前");

        // 文件格式与 MewCode 时代一致：{"text","ts"} 每行一条
        String firstLine = Files.readAllLines(f).get(0);
        assertTrue(firstLine.contains("\"text\"") && firstLine.contains("\"ts\""), firstLine);
    }

    @Test
    void legacyFormatAndCorruptLinesTolerated() throws Exception {
        Path f = dir.resolve("mixed.jsonl");
        Files.writeString(f, """
                {"text":"旧格式条目","ts":1786159967}
                not-json{{
                {"text":""}
                {"other":"无text字段"}

                {"text":"新条目","ts":1786159968}
                """);
        List<String> loaded = ChatModel.loadPromptHistory(f);
        assertEquals(List.of("新条目", "旧格式条目"), loaded, "坏行/空 text/空行跳过，旧格式兼容");
    }

    @Test
    void capsAtFiftyNewestEntries() throws Exception {
        Path f = dir.resolve("many.jsonl");
        var sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append("{\"text\":\"条目").append(i).append("\",\"ts\":1}\n");
        }
        Files.writeString(f, sb.toString());

        List<String> loaded = ChatModel.loadPromptHistory(f);
        assertEquals(50, loaded.size());
        assertEquals("条目60", loaded.get(0), "最新在前");
        assertEquals("条目11", loaded.get(49), "最旧的被截断");
    }

    @Test
    void missingFileYieldsEmpty() {
        assertTrue(ChatModel.loadPromptHistory(dir.resolve("nope.jsonl")).isEmpty());
    }
}
