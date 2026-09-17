package com.novacode.context;

import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;
import com.novacode.protocol.LlmClient;
import com.novacode.protocol.StreamEvent;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 结构化摘要生成（第 8 章 F4/F5）。
 *
 * <p>持有一个独立的、未设置任何工具的 {@link LlmClient}——工具列表为空 + 提示词
 * 双重禁止，模型无法调用工具。摘要提示词要求先写 {@code <draft>} 分析草稿、再写
 * {@code <summary>} 正式摘要；返回后只提取 {@code <summary>} 段，草稿丢弃（F5）。</p>
 */
public class Summarizer {

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个对话上下文压缩助手。请把下面这段对话压缩成一份结构化摘要，用于替换较早的对话历史。

            要求：
            1. 不要调用任何工具，只输出文本。
            2. 先在 <draft>...</draft> 里写你的分析草稿（梳理对话脉络与关键信息）。
            3. 然后在 <summary>...</summary> 里写正式摘要。只保留 <summary> 部分，<draft> 会被丢弃。

            <summary> 必须包含以下固定部分，用 Markdown 二级标题分行：
            ## 任务目标
            （用户最初要完成什么）
            ## 已完成的工作
            （已执行的关键操作、工具调用及其重要结果）
            ## 关键决策与结论
            （做了哪些决策、得到哪些结论、重要的文件/代码变更）
            ## 当前状态
            （现在进行到哪一步，最近在做什么）
            ## 待办事项
            （下一步要做什么、未完成的任务）

            注意：已存盘的工具结果在对话里只有预览和文件路径，摘要里只需记录其位置和要点，不要臆测完整内容。
            """;

    private LlmClient client;

    public Summarizer(ProviderConfig config) {
        // 独立客户端，不 setTools —— 无工具可调（F5）
        this.client = LlmClient.create(config, null);
    }

    /** /model 切换后重建摘要客户端，跟随主 provider。 */
    public void switchProvider(ProviderConfig config) {
        this.client = LlmClient.create(config, null);
    }

    /**
     * 对消息列表生成正式摘要（草稿已丢弃）。
     *
     * @throws Exception 摘要调用失败（HTTP 错误、流超时、流 Error）
     */
    public String summarize(List<ChatMessage> messages) throws Exception {
        BlockingQueue<StreamEvent> queue = client.stream(messages, SUMMARY_SYSTEM_PROMPT);
        var sb = new StringBuilder();
        while (true) {
            StreamEvent evt = queue.poll(90, TimeUnit.SECONDS);
            if (evt == null) {
                throw new Exception("摘要流超时");
            }
            switch (evt) {
                case StreamEvent.TextDelta td -> sb.append(td.text());
                case StreamEvent.ThinkingDelta ignored -> {}
                case StreamEvent.StreamEnd se -> {
                    return extractSummary(sb.toString());
                }
                case StreamEvent.Error err -> throw new Exception(err.message());
                default -> {}
            }
        }
    }

    /** 提取 {@code <summary>} 段；缺失时降级为整段（去首尾空白）。 */
    static String extractSummary(String raw) {
        if (raw == null) return "";
        int s = raw.indexOf("<summary>");
        int e = raw.indexOf("</summary>");
        if (s >= 0 && e > s) {
            return raw.substring(s + "<summary>".length(), e).trim();
        }
        if (s >= 0) {
            return raw.substring(s + "<summary>".length()).trim();
        }
        return raw.trim();
    }
}
