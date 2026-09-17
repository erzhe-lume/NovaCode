package com.novacode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 指令文件 @include 展开：相对路径、代码块豁免、循环防护、越界保留。 */
class InstructionIncludeTest {

    @TempDir
    Path dir;

    private String expand(String content, String baseDir) {
        return InstructionLoader.expandIncludes(
                content, baseDir, new HashSet<>(), 0, List.of(dir.toAbsolutePath().normalize()));
    }

    @Test
    void relativeIncludeExpands() throws Exception {
        Files.writeString(dir.resolve("b.md"), "B 的内容");
        String out = expand("A 开头\n@./b.md\nA 结尾", dir.toString());
        assertTrue(out.contains("A 开头"));
        assertTrue(out.contains("B 的内容"), "@include 应展开: " + out);
        assertTrue(out.contains("A 结尾"));
        assertTrue(out.contains("included from ./b.md"), "应标注包含来源");
    }

    @Test
    void includeInsideCodeBlockIsLeftAlone() throws Exception {
        Files.writeString(dir.resolve("b.md"), "B 的内容");
        String out = expand("```\n@./b.md\n```", dir.toString());
        assertFalse(out.contains("B 的内容"), "代码块内不应展开: " + out);
        assertTrue(out.contains("@./b.md"));
    }

    @Test
    void circularIncludesTerminate() throws Exception {
        Files.writeString(dir.resolve("a.md"), "A\n@./b.md\n");
        Files.writeString(dir.resolve("b.md"), "B\n@./a.md\n");
        String out = expand("入口\n@./a.md\n", dir.toString());
        assertTrue(out.contains("A"));
        assertTrue(out.contains("B"));
        assertEquals(1, out.split("B\\n", -1).length - 1, "循环包含只出现一次");
    }

    @Test
    void outsideAllowedRootsKeepsOriginalLine() throws Exception {
        Path outside = dir.resolveSibling(dir.getFileName() + "-out");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.md"), "越界内容");
        // baseDir = dir，目标在 dir 之外 → 不在 allowedRoots
        String out = expand("@../" + dir.getFileName() + "-out/secret.md", dir.toString());
        assertFalse(out.contains("越界内容"), "越界包含应被拒绝");
        assertTrue(out.contains("@../"), "原始行保留");
    }

    @Test
    void mentionSyntaxIsNotInclude() {
        assertNull(InstructionLoader.parseInclude("@username"), "@提及不误判");
        assertNull(InstructionLoader.parseInclude("@@escape"), "@@ 转义不处理");
        assertNull(InstructionLoader.parseInclude("@no-space allowed"), "带空格不是路径");
        assertNull(InstructionLoader.parseInclude("@bare"), "无路径前缀不处理");
        assertEquals("./b.md", InstructionLoader.parseInclude("@./b.md"));
        assertEquals("../up/x.md", InstructionLoader.parseInclude("@../up/x.md"));
        assertEquals("~/home/x.md", InstructionLoader.parseInclude("@~/home/x.md"));
    }
}
