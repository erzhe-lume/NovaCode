package com.novacode.tool.impl;

import com.novacode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Bash 工具（Windows cmd 路径）：中文输出解码、超时保留输出、退出码。 */
class BashToolTest {

    @TempDir
    Path dir;

    private BashTool bash() {
        var b = new BashTool();
        b.setCwd(dir);
        return b;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    void chineseOutputDecodesWithoutMojibake() {
        assumeTrue(windows());
        ToolResult result = bash().execute(Map.of("command", "echo 中文输出测试"));
        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("中文输出测试"), "中文不应乱码: " + result.output().trim());
    }

    @Test
    void timeoutIncludesCapturedOutput() {
        assumeTrue(windows());
        ToolResult result = bash().execute(Map.of(
                "command", "echo BEFORE_TIMEOUT && ping -n 30 127.0.0.1 > nul",
                "timeout", 3));
        assertTrue(result.isError(), "应报超时");
        assertTrue(result.output().contains("timed out"), result.output());
        assertTrue(result.output().contains("BEFORE_TIMEOUT"), "超时错误应带上已捕获输出: " + result.output());
    }

    @Test
    void nonZeroExitCodeIsReported() {
        assumeTrue(windows());
        ToolResult result = bash().execute(Map.of("command", "cmd /c exit 3"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("Exit code 3"), result.output());
    }

    @Test
    void plainEchoSucceeds() {
        assumeTrue(windows());
        ToolResult result = bash().execute(Map.of("command", "echo hello-novacode"));
        assertFalse(result.isError());
        assertTrue(result.output().contains("hello-novacode"));
    }
}
