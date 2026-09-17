package com.novacode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ANSI 注入防御：ChatModel.sanitize 终端控制码剥离（重试判定测试在 protocol 包）。 */
class SanitizeTest {

    // ── sanitize：终端控制码注入防御 ─────────────────────────────────

    @Test
    void stripsAnsiColorSequences() {
        String out = ChatModel.sanitize("\033[31m红字\033[0m 尾随");
        assertEquals("红字 尾随", out);
    }

    @Test
    void stripsCursorControlAndClearScreen() {
        String out = ChatModel.sanitize("\033[2J\033[H清屏攻击");
        assertEquals("清屏攻击", out);
    }

    @Test
    void stripsOscTitleSequences() {
        String out = ChatModel.sanitize("\033]0;恶意标题\07正文");
        assertEquals("正文", out);
    }

    @Test
    void keepsTabAndNewlineDropsOtherControlChars() {
        String out = ChatModel.sanitize("a\tb\nc\rd\07e");
        assertEquals("a\tb\ncde", out, "tab/newline 保留，CR 与 C0 剥离");
    }

    @Test
    void unterminatedEscapeIsDropped() {
        String out = ChatModel.sanitize("ok\033[31");
        assertEquals("ok", out, "未终结的转义序列应整体丢弃");
    }

    @Test
    void plainTextUnchanged() {
        String s = "普通中文文本 with english 123 !@#";
        assertEquals(s, ChatModel.sanitize(s));
        assertEquals("", ChatModel.sanitize(null));
        assertEquals("", ChatModel.sanitize(""));
    }
}
