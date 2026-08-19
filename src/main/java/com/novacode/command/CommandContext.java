package com.novacode.command;

import com.novacode.permission.PermissionMode;

/**
 * 界面控制接口（第 10 章 spec F4）：命令实现只依赖此接口，不绑定渲染框架。
 *
 * <p>spec 要求「至少」支持显示消息 / 发送用户消息 / 切换模式 / 查 Token / 刷状态；
 * 为覆盖内置命令（{@code /clear}、{@code /new}、{@code /compact}、{@code /permission}、
 * {@code /status}、{@code /exit}）补充对应的清空、新会话、压缩、查模式、退出方法。</p>
 */
public interface CommandContext {

    /** 显示一条系统 / 工具消息到消息区（不进对话历史）。 */
    void display(String content);

    /** 清空可见消息区（保留对话历史）。 */
    void clearMessages();

    /** 把预设提示词作为用户消息注入对话历史并启动 Agent（PROMPT 类命令）。 */
    void sendPrompt(String content);

    /** 切换权限模式（/plan /do）。 */
    void setMode(PermissionMode mode);

    /** 查询当前权限模式。 */
    PermissionMode getMode();

    /** 查询累计 Token 用量与上下文估算。 */
    TokenStats tokens();

    /** 触发一次上下文压缩，返回人类可读状态（/compact）。 */
    String compactContext();

    /** 开新会话：清空对话历史与消息区，返回新会话 ID（/new）。 */
    String newSession();

    /** 刷新状态栏（模式标记联动；TEA 下每帧自动重绘，接口保留以解耦）。 */
    void refreshStatus();

    /** 退出程序（/exit）。 */
    void quit();

    /** 调用一个 Skill（第 11 章）：shared 激活并触发 Agent；independent 独立执行回流摘要。 */
    void invokeSkill(String name, String args);

    /** 累计 Token 与上下文估算（spec F4「查 Token」）。 */
    record TokenStats(int inputTokens, int outputTokens,
                      int cacheRead, int cacheWrite,
                      long contextTokens, int contextWindow) {}
}
