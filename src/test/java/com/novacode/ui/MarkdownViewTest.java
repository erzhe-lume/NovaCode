package com.novacode.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MarkdownView：块结构渲染与平铺 span。 */
class MarkdownViewTest {

    private static String plain(String s) {
        // 去掉 ANSI 转义便于断言（视 \033[...m 为一个单元整体移除）
        var sb = new StringBuilder(s);
        int i;
        while ((i = sb.indexOf("\033[")) >= 0) {
            int end = sb.indexOf("m", i);
            if (end < 0) break;
            sb.delete(i, end + 1);
        }
        return sb.toString();
    }

    @Test
    void headingIsFirstLine() {
        List<String> lines = MarkdownView.renderLines("# 标题\n\n正文");
        assertFalse(lines.isEmpty());
        assertTrue(plain(lines.get(0)).contains("标题"), lines.get(0));
    }

    @Test
    void fencedCodeBlockRendersWithPrefixAndLanguage() {
        List<String> lines = MarkdownView.renderLines("```java\nint x = 1;\nint y = 2;\n```\n");
        assertTrue(lines.stream().anyMatch(l -> plain(l).contains("‹java›")), lines.toString());
        assertEquals(2, lines.stream().filter(l -> plain(l).contains("│ ")).count(),
                "代码应逐行渲染，尾随空行裁剪");
        assertTrue(lines.stream().anyMatch(l -> plain(l).contains("│ int x = 1;")));
    }

    @Test
    void listsRenderWithMarkers() {
        List<String> lines = MarkdownView.renderLines("- 甲\n- 乙\n\n1. 第一\n2. 第二\n");
        assertTrue(lines.stream().anyMatch(l -> plain(l).contains("•") && plain(l).contains("甲")));
        assertTrue(lines.stream().anyMatch(l -> plain(l).contains("1.") && plain(l).contains("第一")));
        assertTrue(lines.stream().anyMatch(l -> plain(l).contains("2.") && plain(l).contains("第二")));
    }

    @Test
    void inlineCodeAndBoldAndLink() {
        List<String> lines = MarkdownView.renderLines("用 `foo()` 处理 **数据**，见 [docs](https://x.y)\n");
        String all = String.join("\n", lines.stream().map(MarkdownViewTest::plain).toList());
        assertTrue(all.contains("foo()"));
        assertTrue(all.contains("数据"));
        assertTrue(all.contains("(https://x.y)"), "链接应带目标地址");
    }

    @Test
    void blockQuotePrefix() {
        List<String> lines = MarkdownView.renderLines("> 引用内容\n");
        assertTrue(lines.stream().anyMatch(l -> plain(l).contains("▌") && plain(l).contains("引用内容")));
    }

    @Test
    void plainTextIsSingleParagraphLine() {
        List<String> lines = MarkdownView.renderLines("就一段普通文字");
        assertEquals(1, lines.size());
        assertTrue(plain(lines.get(0)).contains("普通文字"));
    }

    @Test
    void blankInputYieldsNoLines() {
        assertTrue(MarkdownView.renderLines("").isEmpty());
        assertTrue(MarkdownView.renderLines(null).isEmpty());
    }

    @Test
    void eachRenderedLineContainsNoEmbeddedNewline() {
        // viewport 计数依赖「一条 entry = 一行」
        List<String> lines = MarkdownView.renderLines(
                "段落一换行\n继续\n\n- 项目`code`\n```py\nx=1\n```\n");
        for (String line : lines) {
            assertFalse(line.contains("\n"), "渲染行不应内嵌换行: " + plain(line));
        }
    }
}
