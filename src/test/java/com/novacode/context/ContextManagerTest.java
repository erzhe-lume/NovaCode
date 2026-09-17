package com.novacode.context;

import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 上下文管理：兜底边界对齐、预防层存盘、token 估算锚定。 */
class ContextManagerTest {

    @TempDir
    static Path tempDir;

    private static String realUserDir;

    @BeforeAll
    static void redirectUserDir() {
        realUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterAll
    static void restoreUserDir() {
        System.setProperty("user.dir", realUserDir);
    }

    private ContextManager newManager() {
        var cfg = new ProviderConfig("t", "openai-compat", "sk", "m", "https://example.com", false);
        cfg.setContextWindow(64_000);
        return new ContextManager(cfg);
    }

    /** 生成约 {@code tokens} token 的消息（TokenEstimator 按字符粗估，中文约 1 char/token 上限）。 */
    private static ChatMessage bigUser(String tag, int chars) {
        return new ChatMessage(ChatMessage.Role.USER, tag + "x".repeat(chars));
    }

    @Test
    void smallHistoryNeedsNoCompression() {
        var cm = newManager();
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 50; i++) history.add(new ChatMessage(ChatMessage.Role.USER, "m" + i));
        assertEquals(0, cm.findBoundary(history), "远低于窗口阈值应返回 0（不压缩）");
        assertTrue(cm.compressNow(history).startsWith("无需压缩"));
    }

    @Test
    void boundaryLandsOnNonToolMessage() {
        var cm = newManager();
        // TokenEstimator: 4 chars/token。构造回扫时恰好停在 TOOL 上的场景：
        // [USER tiny x2][TOOL 50000chars=12500tok][USER 1000chars x5]
        // 回扫：5×250=1250(<10000,kept5) → TOOL:13750(≥10000,kept6) → b=TOOL 下标，
        // 对齐循环必须把 b 推过 TOOL 段落在 USER 上。
        var history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "u0"));
        history.add(new ChatMessage(ChatMessage.Role.USER, "u1"));
        history.add(ChatMessage.toolResult("t1", "T".repeat(50_000)));
        for (int i = 0; i < 5; i++) history.add(bigUser("t" + i, 1_000));

        int b = cm.findBoundary(history);
        assertTrue(b > 0, "超过保留阈值应有压缩边界");
        assertNotEquals(ChatMessage.Role.TOOL, history.get(b).getRole(),
                "保留段不能以 TOOL 开头（孤儿 tool_call 防护）");
        assertEquals(ChatMessage.Role.USER, history.get(b).getRole());
        assertEquals(3, b, "边界应对齐到 TOOL 段之后的第一条 USER 上");
        assertTrue(b < history.size());
    }

    @Test
    void oversizedToolResultSpillsToDisk() {
        var cm = newManager();
        var history = new ArrayList<ChatMessage>(List.of(
                new ChatMessage(ChatMessage.Role.USER, "hi"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(
                        new ChatMessage.ToolCall("t1", "Bash", Map.of()))),
                ChatMessage.toolResult("t1", "y".repeat(40_000)))); // ~40K token > 8K 阈值

        String status = cm.prepareBeforeRequest(history);

        assertTrue(status.contains("存盘"), "应报告存盘: " + status);
        String content = history.get(2).getContent();
        assertTrue(content.startsWith("[工具结果已存盘"), "原内容应替换为存盘通知: " + content.substring(0, 30));
        assertTrue(content.contains(".novacode/spill/tool_"), "通知应带 spill 路径");
        assertTrue(content.length() < 1_000, "历史里只应留预览");
    }

    @Test
    void smallToolResultStaysInline() {
        var cm = newManager();
        var history = new ArrayList<ChatMessage>(List.of(
                new ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage.toolResult("t1", "tiny result")));

        cm.prepareBeforeRequest(history);

        assertEquals("tiny result", history.get(1).getContent(), "小结果不应被动");
    }

    @Test
    void batchedSmallResultsStayWhenUnderBatchThreshold() {
        var cm = newManager();
        // 4 条 ~500 tok（2K 字符）= 2K token 合计 < 16K 批量阈值
        var history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "hi"));
        for (int i = 0; i < 4; i++) history.add(ChatMessage.toolResult("t" + i, "z".repeat(2_000)));

        cm.prepareBeforeRequest(history);

        for (int i = 1; i < history.size(); i++) {
            assertEquals(2_000, history.get(i).getContent().length(), "批量阈值内不应存盘");
        }
    }

    @Test
    void usageAnchoringUpdatesEstimate() {
        var cm = newManager();
        var history = new ArrayList<ChatMessage>(List.of(
                new ChatMessage(ChatMessage.Role.USER, "hello world this is a message")));
        long before = cm.estimateCurrent(history);
        cm.onUsage(500, history);
        long after = cm.estimateCurrent(history);
        assertTrue(before > 0, "估算应为正");
        assertTrue(after <= 500 * 4, "锚定后估算应受真实 usage 约束: " + after);
    }
}
