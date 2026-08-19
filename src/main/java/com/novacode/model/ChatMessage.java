package com.novacode.model;

import java.util.List;
import java.util.Map;

/**
 * A single turn in the conversation. Supports both plain text and tool-call
 * messages (used by the Agent loop for proper Function Calling history).
 */
public class ChatMessage {
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    private final Role role;
    private final String content;
    private final boolean isError;

    // Tool-call related fields (only meaningful for ASSISTANT and TOOL roles)
    private final List<ToolCall> toolCalls;
    private final String toolCallId;

    /** An assistant-requested tool call. */
    public record ToolCall(String id, String name, Map<String, Object> arguments) {}

    // ── Constructors ─────────────────────────────────────────────────────

    public ChatMessage(Role role, String content) {
        this(role, content, false, null, null);
    }

    public ChatMessage(Role role, String content, boolean isError) {
        this(role, content, isError, null, null);
    }

    /** Assistant message with tool calls. */
    public ChatMessage(Role role, String content, List<ToolCall> toolCalls) {
        this(role, content, false, toolCalls, null);
    }

    /** Tool-result message. */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, false, null, toolCallId);
    }

    private ChatMessage(Role role, String content, boolean isError,
                        List<ToolCall> toolCalls, String toolCallId) {
        this.role = role;
        this.content = content;
        this.isError = isError;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
    }

    // ── Accessors ────────────────────────────────────────────────────────

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public boolean isError() { return isError; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
