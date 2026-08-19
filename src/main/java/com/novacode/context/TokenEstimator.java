package com.novacode.context;

import com.novacode.model.ChatMessage;

import java.util.List;

/**
 * 近似 Token 估算（第 8 章 F9）。
 *
 * <p>不做精确 tokenizer：用「锚定上一次 API 返回的输入 usage + 对增量按字符数估算」。
 * 每次 LLM 请求返回 usage 时调用 {@link #anchor}，之后 {@link #estimateTotal}
 * 只对「上次以来的新增字符」按 {@link #CHARS_PER_TOKEN} 折算，误差被限制在增量规模。</p>
 */
public class TokenEstimator {

    /** 近似每 token 的字符数（英文/代码约 4 字符/token；中文偏高但由锚定吸收）。 */
    public static final double CHARS_PER_TOKEN = 4.0;

    private long anchorTokens = 0;   // 上次 API 返回的输入 token
    private long anchorChars = 0;    // 当时的历史消息字符数合计

    /** 记录一次真实 usage 与当时的消息字符数（仅在 usage > 0 时更新）。 */
    public void anchor(long inputTokens, long messageChars) {
        if (inputTokens > 0) {
            this.anchorTokens = inputTokens;
            this.anchorChars = messageChars;
        }
    }

    /** 当前估算总量 = 锚点 token + 增量字符 / 每 token 字符数。 */
    public long estimateTotal(long currentMessageChars) {
        long deltaChars = Math.max(0, currentMessageChars - anchorChars);
        return anchorTokens + Math.round(deltaChars / CHARS_PER_TOKEN);
    }

    /** 单条文本的近似 token（至少 1，空串为 0）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    /** 历史消息 content 字符数合计（null 按 0）。 */
    public static long totalChars(List<ChatMessage> history) {
        long sum = 0;
        for (var m : history) {
            String c = m.getContent();
            if (c != null) sum += c.length();
        }
        return sum;
    }
}
