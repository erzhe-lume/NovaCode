package com.novacode.tool.impl;

import com.novacode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        // 被测的是"子进程输出的中文能被正确解码"这一条解码链，而不是参数编组。
        // 因此中文必须由子进程自己读文件产生，不能作为命令参数传入：
        // Windows 创建进程时命令行参数按系统 ANSI 代码页编组，在 ANSI 代码页非 CJK 的
        // 环境（如 GitHub Actions 的 windows-latest，1252）中文会在进程启动前就被
        // 替换成 '?'，此时被测代码永远拿不到原文——那是环境的限制，不是解码缺陷。
        try {
            Files.writeString(dir.resolve("cn.txt"), "中文输出测试", StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("准备测试文件失败: " + e.getMessage());
        }
        ToolResult result = bash().execute(Map.of("command", "type cn.txt"));
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
