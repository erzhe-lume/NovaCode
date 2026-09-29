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
        // 显式把子进程控制台代码页切到 UTF-8：本用例验证的是"UTF-8 输出能被正确解码"，
        // 不应依赖宿主 runner 的默认代码页（GitHub Actions 的 Windows runner 是 OEM 437，
        // 既非 UTF-8 也非 GBK，输出落在探测链之外，会产生环境相关的假失败）。
        // 真实运行环境同理——launch.bat 也先执行 chcp 65001。
        ToolResult result = bash().execute(Map.of("command", "chcp 65001 >nul && echo 中文输出测试"));
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
