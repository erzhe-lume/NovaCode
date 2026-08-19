package com.novacode.teams;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一条团队邮箱消息（第 15 章 F4/N1/N2/N3）。
 *
 * <p>携带发送者、正文、时间戳、是否已读、摘要，以及可选的结构化协议字段
 * （{@code type} / {@code requestId} / {@code approve}）。落盘由 {@link #toMap}/
 * {@link #fromMap} 完成（跨进程共享），摘要与时间戳在便捷构造器 {@link #MailMessage(String, String)}
 * 中自动派生，默认未读。</p>
 *
 * <p>缺省语义 fail-closed：{@link TeamProtocol#approved} 只在 approve 明确为 true 时放行。</p>
 */
public record MailMessage(
        String from,
        String text,
        String timestamp,
        boolean read,
        String summary,
        String type,
        String requestId,
        Boolean approve) {

    /** 摘要最大长度（超出截断补 …）。 */
    public static final int SUMMARY_MAX = 120;

    /** 紧凑构造器：校验必填字段（from/text 不得为空）。 */
    public MailMessage {
        if (from == null || from.isBlank()) throw new IllegalArgumentException("from required");
        Objects.requireNonNull(text, "text");
    }

    /** 便捷构造器：自动补 ISO 时间戳、默认未读、自动派生摘要、type=TEXT。 */
    public MailMessage(String from, String text) {
        this(from, text, Instant.now().toString(), false, summaryOf(text), TeamProtocol.TEXT, null, null);
    }

    /** 复制并修改已读标记（其余字段不变）。 */
    public MailMessage withRead(boolean read) {
        return new MailMessage(from, text, timestamp, read, summary, type, requestId, approve);
    }

    /** 摘要派生：压空白后截断到 {@link #SUMMARY_MAX} 字符。 */
    public static String summaryOf(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= SUMMARY_MAX) return flat;
        return flat.substring(0, SUMMARY_MAX) + "…";
    }

    // ── JSON 磁盘形态（Map 往返，参照 WorktreeSessionStore 模式）────────────

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("text", text);
        m.put("timestamp", timestamp);
        m.put("read", read);
        m.put("summary", summary);
        m.put("type", type);
        m.put("request_id", requestId);
        m.put("approve", approve);
        return m;
    }

    /** 从磁盘 Map 还原；缺失/非法字段容错。 */
    public static MailMessage fromMap(Map<String, Object> m) {
        if (m == null) return null;
        String from = str(m, "from");
        String text = str(m, "text");
        if (from == null || text == null) return null;
        String ts = str(m, "timestamp");
        if (ts == null) ts = Instant.now().toString();
        boolean read = Boolean.TRUE.equals(m.get("read"));
        String summary = str(m, "summary");
        if (summary == null) summary = summaryOf(text);
        String type = str(m, "type");
        if (type == null) type = TeamProtocol.TEXT;
        String requestId = str(m, "request_id");
        Boolean approve = null;
        Object a = m.get("approve");
        if (a instanceof Boolean b) approve = b;
        return new MailMessage(from, text, ts, read, summary, type, requestId, approve);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s ? s : null;
    }
}
