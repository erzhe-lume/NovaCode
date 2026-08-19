package com.novacode.protocol;

import java.util.Map;

/** Stream events including tool calls. */
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}
    record ToolCallDelta(String text) implements StreamEvent {}
    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) implements StreamEvent {}
    record StreamEnd(String stopReason) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
    record Usage(int inputTokens, int outputTokens, int cacheRead, int cacheWrite) implements StreamEvent {
        /** Cold-start / non-cache providers: usage carries no cache breakdown. */
        Usage(int inputTokens, int outputTokens) {
            this(inputTokens, outputTokens, 0, 0);
        }
    }
}
