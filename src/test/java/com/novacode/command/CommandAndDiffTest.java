package com.novacode.command;

import com.novacode.tool.impl.DiffUtil;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 斜杠命令解析 + EditFile 差异摘要。 */
class CommandAndDiffTest {

    // ── CommandParser ────────────────────────────────────────────────

    @Test
    void parsesNameAndArgs() {
        var parsed = CommandParser.parse("/resume 20260915-2313").orElseThrow();
        assertEquals("resume", parsed.name());
        assertEquals("20260915-2313", parsed.args());
    }

    @Test
    void lowercasesCommandName() {
        assertEquals("compact", CommandParser.parse("/COMPACT").orElseThrow().name());
    }

    @Test
    void noArgsGivesEmptyString() {
        var parsed = CommandParser.parse("  /clear  ").orElseThrow();
        assertEquals("clear", parsed.name());
        assertEquals("", parsed.args());
    }

    @Test
    void nonSlashInputIsEmpty() {
        assertTrue(CommandParser.parse("hello world").isEmpty());
        assertTrue(CommandParser.parse("").isEmpty());
        assertTrue(CommandParser.parse(null).isEmpty());
        assertTrue(CommandParser.parse("   ").isEmpty());
    }

    @Test
    void bareSlashIsEmpty() {
        assertTrue(CommandParser.parse("/").isEmpty());
        assertTrue(CommandParser.parse("/   ").isEmpty());
    }

    @Test
    void argsMayContainSlashes() {
        var parsed = CommandParser.parse("/review src/main/java").orElseThrow();
        assertEquals("review", parsed.name());
        assertEquals("src/main/java", parsed.args());
    }

    // ── DiffUtil ─────────────────────────────────────────────────────

    @Test
    void singleLineChangeReportsOneAndOne() {
        String diff = DiffUtil.buildDiff("old line\nshared\n", "new line\nshared\n");
        assertTrue(diff.startsWith("1 removal, 1 addition"), diff);
        assertTrue(diff.contains("- old line"));
        assertTrue(diff.contains("+ new line"));
        assertFalse(diff.contains("shared"), "未变行不进 diff");
    }

    @Test
    void identicalContentReportsZero() {
        String diff = DiffUtil.buildDiff("same\n", "same\n");
        assertTrue(diff.startsWith("0 removals, 0 additions"), diff);
    }

    @Test
    void pureAdditionAtEnd() {
        String diff = DiffUtil.buildDiff("a\n", "a\nb\nc\n");
        assertTrue(diff.startsWith("0 removals, 2 additions"), diff);
        assertTrue(diff.contains("+ b"));
        assertTrue(diff.contains("+ c"));
    }

    @Test
    void multiLineReplacement() {
        String diff = DiffUtil.buildDiff("keep\nold1\nold2\nkeep2\n", "keep\nnew1\nkeep2\n");
        assertTrue(diff.startsWith("2 removals, 1 addition"), diff);
        assertTrue(diff.contains("- old1"));
        assertTrue(diff.contains("+ new1"));
    }
}
