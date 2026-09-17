package com.novacode.tool.impl;

import com.novacode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 工具层：ReadFile 截断与续读、EditFile replace_all 与编码往返、Grep 输出模式、Glob。 */
class ToolsTest {

    @TempDir
    Path dir;

    // ── ReadFile ─────────────────────────────────────────────────────

    private ReadFileTool reader() {
        var r = new ReadFileTool();
        r.setCwd(dir);
        return r;
    }

    @Test
    void readFileTruncatesOverlongLines() {
        var r = reader();
        Path f = dir.resolve("longline.txt");
        assertDoesNotThrow(() -> Files.writeString(f, "a".repeat(2_000) + "\nshort\n"));

        ToolResult result = r.execute(Map.of("file_path", "longline.txt"));

        assertFalse(result.isError());
        assertTrue(result.output().contains("行截断"), "超长行应截断: " + result.output());
        assertTrue(result.output().contains("short"), "后续行应保留");
    }

    @Test
    void readFileCapsTotalOutputWithOffsetHint() {
        var r = reader();
        Path f = dir.resolve("big.txt");
        var sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("line-").append(i).append("-").append("c".repeat(400)).append("\n");
        assertDoesNotThrow(() -> Files.writeString(f, sb.toString()));

        ToolResult result = r.execute(Map.of("file_path", "big.txt"));

        assertTrue(result.output().contains("offset="), "总量截断应提示续读 offset: " + result.output().length());
        assertTrue(result.output().contains("截断"));
    }

    @Test
    void readFileOffsetContinuesFromRightLine() {
        var r = reader();
        Path f = dir.resolve("lines.txt");
        var sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) sb.append("line").append(i).append("\n");
        assertDoesNotThrow(() -> Files.writeString(f, sb.toString()));

        ToolResult result = r.execute(Map.of("file_path", "lines.txt", "offset", 90));

        assertTrue(result.output().startsWith("91\t"), "offset=90 应从第 91 行开始: " + result.output());
    }

    @Test
    void readFileDecodesGbkFiles() {
        var r = reader();
        Path f = dir.resolve("gbk.txt");
        assertDoesNotThrow(() -> Files.write(f, "中文内容测试".getBytes(Charset.forName("GBK"))));

        ToolResult result = r.execute(Map.of("file_path", "gbk.txt"));

        assertTrue(result.output().contains("中文内容测试"), "GBK 文件应正确解码: " + result.output());
    }

    // ── EditFile ─────────────────────────────────────────────────────

    private EditFileTool editor() {
        var e = new EditFileTool();
        e.setCwd(dir);
        return e;
    }

    private void writeFileUtf8(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void editFileRejectsMultipleMatchesWithoutReplaceAll() throws Exception {
        writeFileUtf8("dup.txt", "a b\na b\n");
        ToolResult result = editor().execute(Map.of(
                "file_path", "dup.txt", "old_string", "a b", "new_string", "c"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("replace_all"), "错误信息应提示 replace_all");
    }

    @Test
    void editFileReplaceAllReplacesEveryOccurrence() throws Exception {
        writeFileUtf8("ra.txt", "x = 1; y = x;\nprint(x);\n");

        ToolResult result = editor().execute(Map.of(
                "file_path", "ra.txt", "old_string", "x", "new_string", "val",
                "replace_all", true));

        assertFalse(result.isError(), result.output());
        String content = Files.readString(dir.resolve("ra.txt"));
        assertEquals("val = 1; y = val;\nprint(val);\n", content);
        assertTrue(result.output().contains("3 occurrences"), "应报告替换次数");
    }

    @Test
    void editFileSingleMatchDoesNotTouchOthers() throws Exception {
        writeFileUtf8("one.txt", "alpha alpha\n");

        ToolResult result = editor().execute(Map.of(
                "file_path", "one.txt", "old_string", "alpha alpha", "new_string", "beta"));

        assertFalse(result.isError());
        assertEquals("beta\n", Files.readString(dir.resolve("one.txt")));
    }

    @Test
    void editFileKeepsGbkEncodingAfterEdit() throws Exception {
        Path f = dir.resolve("gbk-src.txt");
        String original = "名称 = 旧值\n编号 = 1\n";
        Files.write(f, original.getBytes(Charset.forName("GBK")));

        ToolResult result = editor().execute(Map.of(
                "file_path", "gbk-src.txt", "old_string", "旧值", "new_string", "新值"));

        assertFalse(result.isError(), result.output());
        byte[] bytes = Files.readAllBytes(f);
        // 若被误写成 UTF-8，GBK 解码会出现替换符或乱码
        String asGbk = new String(bytes, Charset.forName("GBK"));
        assertTrue(asGbk.contains("名称 = 新值"), "GBK 编码应保持: " + asGbk);
        String asUtf8 = new String(bytes, Charset.forName("UTF-8"));
        assertFalse(asUtf8.contains("名称"), "不应被转成 UTF-8");
    }

    @Test
    void editFileMissingOldStringErrors() throws Exception {
        writeFileUtf8("miss.txt", "hello\n");
        ToolResult result = editor().execute(Map.of(
                "file_path", "miss.txt", "old_string", "nope", "new_string", "x"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("not found"));
    }

    @Test
    void editFilePreservesUtf8Bom() throws Exception {
        Path f = dir.resolve("bom.txt");
        Files.writeString(f, "\uFEFFname = old\n");
        ToolResult result = editor().execute(Map.of(
                "file_path", "bom.txt", "old_string", "old", "new_string", "new"));
        assertFalse(result.isError(), result.output());
        byte[] bytes = Files.readAllBytes(f);
        assertEquals(0xEF, bytes[0] & 0xFF, "BOM 应保留");
        assertEquals(0xBB, bytes[1] & 0xFF);
        assertEquals(0xBF, bytes[2] & 0xFF);
    }

    // ── Grep ─────────────────────────────────────────────────────────

    private GrepTool grepper() throws Exception {
        Path pkg = dir.resolve("pkg");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("A.java"), "class A {\n  int todo = 1;\n}\n");
        Files.writeString(pkg.resolve("B.java"), "class B {\n  // a todo fix\n}\n");
        var g = new GrepTool();
        g.setCwd(dir);
        return g;
    }

    @Test
    void grepContentMode() throws Exception {
        ToolResult result = grepper().execute(Map.of("pattern", "todo", "path", "pkg"));
        assertFalse(result.isError());
        assertTrue(result.output().contains("A.java:2"), result.output());
        assertTrue(result.output().contains("B.java:2"), result.output());
    }

    @Test
    void grepFilesModeReturnsPathsOnly() throws Exception {
        ToolResult result = grepper().execute(Map.of(
                "pattern", "todo", "path", "pkg", "output_mode", "files_with_matches"));
        assertTrue(result.output().contains("A.java"));
        assertTrue(result.output().contains("B.java"));
        assertFalse(result.output().contains("int todo"), "files 模式不应输出内容行");
    }

    @Test
    void grepCountMode() throws Exception {
        ToolResult result = grepper().execute(Map.of(
                "pattern", "todo", "path", "pkg", "output_mode", "count"));
        assertTrue(result.output().contains("A.java:1"), result.output());
        assertTrue(result.output().contains("B.java:1"), result.output());
    }

    @Test
    void grepHeadLimitTruncates() throws Exception {
        ToolResult result = grepper().execute(Map.of(
                "pattern", "todo", "path", "pkg", "head_limit", 1));
        assertTrue(result.output().contains("limited to first 1"), result.output());
    }

    @Test
    void grepUnknownModeErrors() throws Exception {
        ToolResult result = grepper().execute(Map.of(
                "pattern", "x", "path", "pkg", "output_mode", "bogus"));
        assertTrue(result.isError());
    }

    @Test
    void grepNoMatches() throws Exception {
        ToolResult result = grepper().execute(Map.of("pattern", "zzz-not-there", "path", "pkg"));
        assertFalse(result.isError());
        assertTrue(result.output().contains("No matches"));
    }

    @Test
    void grepParallelBatchesPreserveOrder() throws Exception {
        // >64 个文件跨批，验证批间保序（结果文件名按字典序递增）
        Path many = dir.resolve("many");
        Files.createDirectories(many);
        for (int i = 0; i < 130; i++) {
            Files.writeString(many.resolve(String.format("f%03d.txt", i)), "needle here\n");
        }
        GrepTool g = new GrepTool();
        g.setCwd(dir);
        ToolResult result = g.execute(Map.of("pattern", "needle", "path", "many"));
        assertFalse(result.isError());
        String[] lines = result.output().split("\n");
        assertTrue(lines.length >= 130, "应匹配全部文件: " + lines.length);
        String prev = "";
        for (String line : lines) {
            assertTrue(line.compareTo(prev) >= 0, "结果应保序: " + line + " after " + prev);
            prev = line;
        }
    }

    // ── WriteFile ────────────────────────────────────────────────────

    @Test
    void writeFileCreatesParentDirectories() throws Exception {
        var w = new WriteFileTool();
        w.setCwd(dir);
        ToolResult result = w.execute(Map.of(
                "file_path", "deep/nested/path/new.txt", "content", "hello"));

        assertFalse(result.isError(), result.output());
        assertEquals("hello", Files.readString(dir.resolve("deep/nested/path/new.txt")));
    }

    @Test
    void writeExistingFileRequiresPriorReadWhenCacheWired() throws Exception {
        Path f = dir.resolve("existing.txt");
        Files.writeString(f, "v1");
        var cache = new com.novacode.tool.FileStateCache();
        var w = new WriteFileTool();
        w.setCwd(dir);
        w.setFileStateCache(cache);

        ToolResult blocked = w.execute(Map.of("file_path", "existing.txt", "content", "v2"));
        assertTrue(blocked.isError(), "未读过的已有文件覆写应被拒");
        assertTrue(blocked.output().contains("must be read"), blocked.output());

        // ReadFile 记录后放行
        var r = new ReadFileTool();
        r.setCwd(dir);
        r.setFileStateCache(cache);
        r.execute(Map.of("file_path", "existing.txt"));

        ToolResult ok = w.execute(Map.of("file_path", "existing.txt", "content", "v2"));
        assertFalse(ok.isError(), ok.output());
        assertEquals("v2", Files.readString(f));
    }

    @Test
    void writeNewFileNeedsNoPriorRead() {
        var w = new WriteFileTool();
        w.setCwd(dir);
        w.setFileStateCache(new com.novacode.tool.FileStateCache());
        ToolResult result = w.execute(Map.of("file_path", "brand-new.txt", "content", "x"));
        assertFalse(result.isError(), "新文件不要求先读: " + result.output());
    }

    // ── Glob ─────────────────────────────────────────────────────────

    @Test
    void globFindsJavaFiles() throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        Files.writeString(src.resolve("B.txt"), "text\n");
        var g = new GlobTool();
        g.setCwd(dir);

        ToolResult result = g.execute(Map.of("pattern", "src/**/*.java"));
        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("A.java"), result.output());
        assertFalse(result.output().contains("B.txt"));
    }
}
