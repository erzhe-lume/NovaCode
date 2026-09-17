package com.novacode.context;

import com.novacode.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Token 估算：4 字符/token 近似、锚定增量。 */
class TokenEstimatorTest {

    @Test
    void estimateTokensBasics() {
        assertEquals(0, TokenEstimator.estimateTokens(null));
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(1, TokenEstimator.estimateTokens("a"));
        assertEquals(1, TokenEstimator.estimateTokens("abcd"));
        assertEquals(2, TokenEstimator.estimateTokens("abcde"));
        assertEquals(250, TokenEstimator.estimateTokens("x".repeat(1000)));
    }

    @Test
    void totalCharsSumsContents() {
        long total = TokenEstimator.totalChars(List.of(
                new ChatMessage(ChatMessage.Role.USER, "12345"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, (String) null),
                new ChatMessage(ChatMessage.Role.USER, "abc")));
        assertEquals(8, total, "null content 按 0 计");
    }

    @Test
    void anchoredEstimateAddsIncrementalChars() {
        var estimator = new TokenEstimator();
        // 锚定：400 字符 → 100 token
        estimator.anchor(100, 400);
        // 增量 400 字符 → +100 token
        assertEquals(200, estimator.estimateTotal(800));
        assertEquals(100, estimator.estimateTotal(400), "无增量按锚点");
        assertEquals(100, estimator.estimateTotal(100), "缩小不产生负增量");
    }

    @Test
    void zeroUsageDoesNotAnchor() {
        var estimator = new TokenEstimator();
        estimator.anchor(0, 999);
        assertEquals(250, estimator.estimateTotal(1000), "usage=0 不更新锚点");
    }
}
