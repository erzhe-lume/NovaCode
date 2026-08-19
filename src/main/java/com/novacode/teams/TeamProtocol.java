package com.novacode.teams;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 团队结构化协议消息（第 15 章 F4/N3）。
 *
 * <p>定义消息类型常量与工厂。requestId 用 {@link SecureRandom} 生成跨进程不撞的
 * {@code req-<16 hex>} 标识，供审批应答/关闭应答对回请求。</p>
 *
 * <p>fail-closed 判定：{@link #approved} 只在 approve 字段明确为 true 时返回 true；
 * {@link #isShutdownRequest} 识别类型或 {@code [shutdown]} 文本前缀。</p>
 */
public final class TeamProtocol {

    public static final String TEXT = "text";
    public static final String SHUTDOWN_REQUEST = "shutdown_request";
    public static final String SHUTDOWN_RESPONSE = "shutdown_response";
    public static final String PLAN_APPROVAL_REQUEST = "plan_approval_request";
    public static final String PLAN_APPROVAL_RESPONSE = "plan_approval_response";

    /** 文本形态的关闭请求前缀（兼容只发文本的调用方）。 */
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TeamProtocol() {}

    /** 生成跨进程不撞的请求 id：{@code req-} + 16 个十六进制字符。 */
    public static String newRequestId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("req-");
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 结构化消息工厂：指定类型/requestId/approve，时间戳与摘要自动派生。 */
    public static MailMessage structured(String from, String text, String type, String requestId, Boolean approve) {
        return new MailMessage(from, text, Instant.now().toString(), false,
                MailMessage.summaryOf(text), type, requestId, approve);
    }

    public static MailMessage shutdownRequest(String from) {
        return structured(from, "[shutdown] shutdown requested", SHUTDOWN_REQUEST, newRequestId(), null);
    }

    public static MailMessage shutdownResponse(String from, String requestId) {
        return structured(from, "[shutdown] shutdown acknowledged", SHUTDOWN_RESPONSE, requestId, Boolean.TRUE);
    }

    /** 审批请求：正文 = 队员产出的完整计划文本。 */
    public static MailMessage planApprovalRequest(String from, String requestId, String planText) {
        return structured(from, planText == null ? "(no plan produced)" : planText,
                PLAN_APPROVAL_REQUEST, requestId, null);
    }

    public static MailMessage planApprovalResponse(String from, String requestId, boolean approve) {
        return structured(from, approve ? "PLAN APPROVED" : "PLAN REJECTED",
                PLAN_APPROVAL_RESPONSE, requestId, approve);
    }

    /** 关闭请求判定：类型匹配或正文以 {@code [shutdown]} 开头。 */
    public static boolean isShutdownRequest(MailMessage msg) {
        if (msg == null) return false;
        if (SHUTDOWN_REQUEST.equals(msg.type())) return true;
        String t = msg.text();
        return t != null && t.startsWith(SHUTDOWN_PREFIX);
    }

    /** fail-closed 批准判定：approve 必须明确为 true。 */
    public static boolean approved(MailMessage msg) {
        return msg != null && Boolean.TRUE.equals(msg.approve());
    }
}
