package com.novacode.agent;

import com.novacode.permission.AskContext;
import com.novacode.permission.HitlOutcome;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent-to-UI event protocol. The Agent loop produces these events; the UI
 * consumes them. Each event type carries exactly the data the UI needs to
 * render — the UI never needs to know how many loop iterations ran.
 *
 * Uses sealed interface so switch expressions get exhaustiveness checking.
 */
public sealed interface AgentEvent {

    /** Streaming text chunk from the model (real-time display). */
    record StreamText(String text) implements AgentEvent {}

    /** The model requested a tool call — UI shows "Running ReadFile ..." */
    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args)
            implements AgentEvent {}

    /** Tool finished executing. isError=true means the tool returned an error. */
    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsedSec)
            implements AgentEvent {}

    /** One LLM round completed. Iteration number for progress display. */
    record TurnComplete(int turn) implements AgentEvent {}

    /** The whole Agent Loop is done. totalTurns is how many LLM rounds ran. */
    record LoopComplete(int totalTurns) implements AgentEvent {}

    /** Token usage for the session (cumulative, updated each turn). */
    record UsageEvent(int inputTokens, int outputTokens, int cacheRead, int cacheWrite)
            implements AgentEvent {}

    /** An error occurred — UI displays it but the session continues. */
    record ErrorEvent(String message) implements AgentEvent {}

    /** A non-error notice (e.g. context compression status) — UI shows it dimmed. */
    record Notice(String message) implements AgentEvent {}

    /** The pipeline landed on Ask — the UI must render a confirmation block and
     *  resolve the future with a {@link HitlOutcome} (spec F8). */
    record PermissionAskEvent(AskContext context, CompletableFuture<HitlOutcome> future)
            implements AgentEvent {}
}
