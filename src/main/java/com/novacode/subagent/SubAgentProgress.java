package com.novacode.subagent;

/**
 * 子 Agent 进度事件（第 13 章）。每次工具调用与完成时各发一次。
 *
 * @param agentType   角色名（spec name）
 * @param description 任务简述
 * @param toolName    刚跑完的工具名，完成为 null
 * @param toolOutput  工具输出摘要，完成为 null
 * @param toolError   工具是否报错
 * @param done        是否已结束
 * @param toolCount   已调用工具次数
 * @param totalTime   自启动起的墙钟秒数
 */
public record SubAgentProgress(
        String agentType,
        String description,
        String toolName,
        String toolOutput,
        boolean toolError,
        boolean done,
        int toolCount,
        double totalTime
) {}
