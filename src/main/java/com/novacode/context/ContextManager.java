package com.novacode.context;

import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理编排（第 8 章）。
 *
 * <p>两层压缩：
 * <ul>
 *   <li>预防层（F2/F3）：工具结果超阈值存盘，对话里只留预览 + 路径。</li>
 *   <li>兜底层（F4/F6）：整体对话逼近窗口上限时生成结构化摘要，压缩早期、保留近期。</li>
 * </ul>
 * 每次发起 LLM 请求前调用 {@link #prepareBeforeRequest}（先预防、再兜底，F10）；
 * 请求返回后调用 {@link #onUsage} 锚定 token 估算（F9）。摘要连续失败达到阈值即熔断（F8）。</p>
 */
public class ContextManager {

    // ── 默认阈值（Token，估算单位见 TokenEstimator）─────────────────────
    private static final int SINGLE_SPILL_TOKENS = 2000;   // 单条工具结果存盘阈值
    private static final int BATCH_SPILL_TOKENS = 4000;    // 单条消息工具结果合计存盘阈值
    private static final int AUTO_MARGIN = 13000;          // 自动触发安全余量（F11）
    private static final int MANUAL_MARGIN = 3000;         // 手动触发安全余量（F11）
    private static final int KEEP_RECENT_TOKENS = 10000;   // 兜底压缩保留的近期 Token
    private static final int KEEP_MIN_MESSAGES = 5;        // 兜底压缩至少保留的消息数
    private static final int MAX_SUMMARY_FAILURES = 3;     // 熔断阈值（F8）
    private static final int PREVIEW_CHARS = 600;          // 存盘后保留的预览长度

    private final int contextWindow;
    private final Path spillDir;
    private final TokenEstimator estimator = new TokenEstimator();
    private final Summarizer summarizer;

    private long spillCounter = 0;
    private int consecutiveFailures = 0;
    private boolean fuseBlown = false;

    public ContextManager(ProviderConfig config) {
        this.contextWindow = config.getContextWindow();
        this.spillDir = Path.of(System.getProperty("user.dir"), ".novacode", "spill");
        try {
            Files.createDirectories(spillDir);
        } catch (IOException ignored) {
            // 存盘目录创建失败时，spill() 会静默降级为不存盘
        }
        this.summarizer = new Summarizer(config);
    }

    // ── Agent 循环钩子 ──────────────────────────────────────────────────

    /**
     * 请求前钩子（F10）：先预防层存盘（F2/F3），再判断是否兜底压缩（F4）。
     * 返回本次动作的人类可读状态；无动作返回空串。
     */
    public String prepareBeforeRequest(List<ChatMessage> history) {
        StringBuilder status = new StringBuilder();
        int spilled = spillOversized(history);
        if (spilled > 0) status.append("已存盘 ").append(spilled).append(" 个超限工具结果");

        if (!fuseBlown && shouldCompress(history, AUTO_MARGIN)) {
            String s = compress(history);
            if (!s.isEmpty()) {
                if (status.length() > 0) status.append("；");
                status.append(s);
            }
        }
        return status.toString();
    }

    /** 请求后钩子（F9）：用本次真实 usage 锚定估算。 */
    public void onUsage(int inputTokens, List<ChatMessage> history) {
        estimator.anchor(inputTokens, TokenEstimator.totalChars(history));
    }

    /** 当前上下文估算（锚定 token + 增量折算，第 10 章 /status 用）。 */
    public long estimateCurrent(List<ChatMessage> history) {
        return estimator.estimateTotal(TokenEstimator.totalChars(history));
    }

    /** 手动触发（F7）：用更窄的 3K 余量判断是否压缩（忽略熔断，成功即复位）。 */
    public String compressNow(List<ChatMessage> history) {
        int spilled = spillOversized(history);
        String s = shouldCompress(history, MANUAL_MARGIN) ? compress(history) : "无需压缩（上下文较短）";
        if (spilled > 0) {
            return ("已存盘 " + spilled + " 个超限工具结果" + (s.isEmpty() ? "" : "；" + s));
        }
        return s;
    }

    // ── 预防层：工具结果存盘 ────────────────────────────────────────────

    /** 扫描历史里的 TOOL 消息，按连续段（单条消息的批量结果）存盘，返回存盘条数。 */
    private int spillOversized(List<ChatMessage> history) {
        int spilled = 0;
        int i = 0;
        while (i < history.size()) {
            if (history.get(i).getRole() != ChatMessage.Role.TOOL) { i++; continue; }
            int j = i;
            while (j < history.size() && history.get(j).getRole() == ChatMessage.Role.TOOL) j++;

            // F2：单条超阈值即存盘
            for (int k = i; k < j; k++) {
                if (TokenEstimator.estimateTokens(history.get(k).getContent()) > SINGLE_SPILL_TOKENS) {
                    if (spill(history, k)) spilled++;
                }
            }

            // F3：段内合计超阈值，按体积从大到小存盘直到回落到阈值内
            long runSum = 0;
            for (int k = i; k < j; k++) {
                runSum += TokenEstimator.estimateTokens(history.get(k).getContent());
            }
            if (runSum > BATCH_SPILL_TOKENS) {
                List<Integer> idxs = new ArrayList<>();
                for (int k = i; k < j; k++) idxs.add(k);
                idxs.sort((a, b) -> Integer.compare(
                        TokenEstimator.estimateTokens(history.get(b).getContent()),
                        TokenEstimator.estimateTokens(history.get(a).getContent())));
                for (int k : idxs) {
                    if (runSum <= BATCH_SPILL_TOKENS) break;
                    int before = TokenEstimator.estimateTokens(history.get(k).getContent());
                    if (spill(history, k)) {
                        runSum -= before - TokenEstimator.estimateTokens(history.get(k).getContent());
                        spilled++;
                    }
                }
            }
            i = j;
        }
        return spilled;
    }

    /** 把第 {@code index} 条消息的完整内容写盘，替换为「预览 + 路径」。成功返回 true。 */
    private boolean spill(List<ChatMessage> history, int index) {
        ChatMessage m = history.get(index);
        String content = m.getContent();
        if (content == null || content.isEmpty()) return false;

        String fileName = "tool_" + (spillCounter++) + ".txt";
        Path file = spillDir.resolve(fileName);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            return false; // 写失败不破坏历史，降级为不存盘
        }

        String preview = content.length() > PREVIEW_CHARS
                ? content.substring(0, PREVIEW_CHARS) + "\n…(截断，全文见文件)"
                : content;
        String relPath = ".novacode/spill/" + fileName;
        String replacement = "[工具结果已存盘: " + relPath + "，共 " + content.length() + " 字符]\n" + preview;
        history.set(index, ChatMessage.toolResult(m.getToolCallId(), replacement));
        return true;
    }

    // ── 兜底层：结构化摘要压缩 ──────────────────────────────────────────

    private boolean shouldCompress(List<ChatMessage> history, int margin) {
        long total = estimator.estimateTotal(TokenEstimator.totalChars(history));
        return total + margin >= contextWindow;
    }

    /** 生成摘要并重建历史。返回人类可读状态；无需压缩时返回空串。 */
    private String compress(List<ChatMessage> history) {
        int b = findBoundary(history);
        if (b <= 0) return "无需压缩（上下文较短）";

        String summary;
        try {
            summary = summarizer.summarize(new ArrayList<>(history.subList(0, b)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "（压缩已取消）";
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_SUMMARY_FAILURES) fuseBlown = true;
            return "压缩失败: " + e.getMessage()
                    + (fuseBlown ? "（连续 " + MAX_SUMMARY_FAILURES + " 次，已熔断）"
                                 : "（第 " + consecutiveFailures + "/" + MAX_SUMMARY_FAILURES + " 次）");
        }

        consecutiveFailures = 0;
        fuseBlown = false;

        List<ChatMessage> kept = new ArrayList<>(history.subList(b, history.size()));
        history.clear();
        history.add(summaryMessage(summary));
        history.add(boundaryMessage());
        history.addAll(kept);
        return "已压缩 " + b + " 条消息 → 摘要 + " + kept.size() + " 条近期";
    }

    /** 从尾部按 Token 往回找保留边界；对齐到安全切点（保留段不得以 TOOL 开头）。 */
    int findBoundary(List<ChatMessage> history) {
        int n = history.size();
        if (n <= KEEP_MIN_MESSAGES) return 0;

        long acc = 0;
        int b = n;
        for (int i = n - 1; i >= 0; i--) {
            acc += TokenEstimator.estimateTokens(history.get(i).getContent());
            int kept = n - i;
            if (acc >= KEEP_RECENT_TOKENS && kept >= KEEP_MIN_MESSAGES) {
                b = i;
                break;
            }
        }
        if (b >= n) return 0; // 全程未到阈值 → 无需压缩

        // 保留段不能以 TOOL 开头（否则其 assistant tool_calls 被摘要掉而成孤儿）
        while (b < n && history.get(b).getRole() == ChatMessage.Role.TOOL) b++;
        if (b >= n) return 0;
        return b;
    }

    // ── 消息构造 ────────────────────────────────────────────────────────

    private static ChatMessage summaryMessage(String summary) {
        return new ChatMessage(ChatMessage.Role.USER,
                "<context-summary>\n以下是较早对话的结构化摘要：\n\n" + summary + "\n</context-summary>");
    }

    private static ChatMessage boundaryMessage() {
        return new ChatMessage(ChatMessage.Role.USER,
                "<system-reminder>\n上下文已压缩：较早的对话已替换为上面的摘要。"
                + "如需已存盘工具结果的完整细节，请用 ReadFile 重新读取对应文件，"
                + "不要仅凭摘要臆测代码或文件内容。\n</system-reminder>");
    }
}
