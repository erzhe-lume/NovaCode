package com.novacode.command;

/** 命令执行模式（第 10 章 spec F3）。 */
public enum CommandType {
    /** 纯本地：只计算并展示，不改状态、不调 AI。 */
    LOCAL,
    /** 影响界面状态：改消息区 / 模式 / 会话等界面状态。 */
    UI_STATE,
    /** 预设提示词：把一段预设文本送进对话并启动 Agent。 */
    PROMPT
}
