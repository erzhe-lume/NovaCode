package com.novacode.agent;

import com.novacode.model.ChatMessage;
import com.novacode.context.ContextManager;
import com.novacode.hook.HookContext;
import com.novacode.hook.HookEngine;
import com.novacode.hook.HookEvent;
import com.novacode.hook.PreToolResult;
import com.novacode.permission.Decision;
import com.novacode.permission.PermissionCancelledException;
import com.novacode.permission.PermissionEngine;
import com.novacode.prompt.PlanModePrompt;
import com.novacode.protocol.LlmClient;
import com.novacode.protocol.StreamEvent;
import com.novacode.teams.CoordinatorPrompt;
import com.novacode.tool.Tool;
import com.novacode.tool.ToolCategory;
import com.novacode.tool.ToolRegistry;
import com.novacode.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * ReAct Agent Loop — the heart of NovaCode.
 *
 * Takes user input, enters a while loop calling LLM → executing tools →
 * feeding results back, until the model decides it's done (no more tool calls)
 * or a stop condition triggers (iteration cap, user cancel, consecutive
 * unknown tools, stream error).
 *
 * Runs on a virtual thread; communicates with the UI solely through
 * {@link AgentEvent} events pushed into a {@link BlockingQueue}.
 */
public class Agent {

    private static final int MAX_ITERATIONS = 50;
    private static final int MAX_UNKNOWN_RUN = 3;

    /** Deterministic plan-file location for plan mode reminders (F6). */
    private static final String PLAN_PATH =
            java.nio.file.Path.of(System.getProperty("user.dir"),
                    ".novacode", "plans", "plan.md").toString();

    private LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final PermissionEngine permissionEngine;
    private final ContextManager contextManager;

    /** 自然结束回调（第 9 章）：模型最终回复无工具调用时触发，用于异步沉淀记忆。 */
    private volatile Consumer<List<ChatMessage>> onNaturalStop;

    /** 工具白名单（第 11 章）：空集 = 不收窄（全工具）；非空 = 仅暴露名单内工具。 */
    private volatile Set<String> toolWhitelist = Set.of();

    /** 工具名过滤器（第 15 章 coordinator）：null = 不过滤；命中才保留（在每轮 computeSchemas 中生效）。 */
    private volatile Predicate<String> toolNameFilter;

    /** coordinator 激活判定（第 15 章）：null = 不注入调度指引。 */
    private volatile Supplier<Boolean> coordinatorActiveFn;

    /** 本轮循环迭代上限（第 13 章）：默认 {@link #MAX_ITERATIONS}，角色 maxTurns 可覆盖。 */
    private volatile int maxIterations = MAX_ITERATIONS;

    /** Hook 引擎（第 12 章）：可为 null，null 时所有 hook 逻辑跳过。 */
    private volatile HookEngine hookEngine;

    public Agent(LlmClient client, ToolRegistry registry, String protocol,
                 PermissionEngine permissionEngine, ContextManager contextManager) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.permissionEngine = permissionEngine;
        this.contextManager = contextManager;
    }

    /** 设置自然结束回调；可为 null。 */
    public void setOnNaturalStop(Consumer<List<ChatMessage>> callback) {
        this.onNaturalStop = callback;
    }

    /** 设置工具白名单（第 11 章 F6）；每轮循环开头生效。空集复位为全工具。 */
    public void setToolWhitelist(Set<String> whitelist) {
        this.toolWhitelist = whitelist == null ? Set.of() : whitelist;
    }

    /** 设置工具名过滤器（第 15 章 F7）；null 复位为不过滤。每轮重算 schema 时生效。 */
    public void setToolNameFilter(Predicate<String> filter) {
        this.toolNameFilter = filter;
    }

    /** 设置 coordinator 激活判定（第 15 章 F7）；null 复位为不注入指引。 */
    public void setCoordinatorActiveFn(Supplier<Boolean> fn) {
        this.coordinatorActiveFn = fn;
    }

    /** 设置本轮循环迭代上限（第 13 章）；非正值复位为默认 {@link #MAX_ITERATIONS}。 */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations > 0 ? maxIterations : MAX_ITERATIONS;
    }

    /** 设置 hook 引擎（第 12 章）；可为 null（关闭 hook）。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    /** 运行时切换 LLM 客户端（/model 换 provider）；仅在循环空闲时调用。 */
    public void setClient(LlmClient newClient) {
        this.client = newClient;
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Launch the Agent Loop. Returns immediately with a queue; the loop runs
     * on a background virtual thread and pushes events into the queue.
     *
     * <p>The caller owns {@code history} and shares it with the loop: the loop
     * appends assistant messages, tool calls and tool results directly to it, so
     * the full conversation (including tool context) survives into the next turn.
     * The caller must not read or mutate it while the loop is running.</p>
     *
     * @param history        conversation so far (mutable, shared with the caller)
     * @param systemPrompt   the system prompt to use
     * @param planMode       if true, only read-only tools are exposed
     * @return a blocking queue of AgentEvent for the UI to consume
     */
    /** Handle to the currently running loop thread (for cancel via interrupt). */
    private volatile Thread runningThread;

    public BlockingQueue<AgentEvent> run(List<ChatMessage> history,
                                         String systemPrompt, boolean planMode) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        Thread t = Thread.startVirtualThread(() -> {
            try {
                agentLoop(history, systemPrompt, planMode, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
        runningThread = t;
        return queue;
    }

    /** Interrupt the running loop (best-effort; unblocks poll()/sleep/queue.put). */
    public void interrupt() {
        Thread t = runningThread;
        if (t != null) t.interrupt();
    }

    /** Join the running loop for up to {@code ms} millis (bounded). */
    public void joinCurrent(long ms) {
        Thread t = runningThread;
        if (t != null) {
            try { t.join(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public boolean isRunning() {
        Thread t = runningThread;
        return t != null && t.isAlive();
    }

    // ── Main Loop ────────────────────────────────────────────────────────

    private void agentLoop(List<ChatMessage> history, String systemPrompt,
                           boolean planMode, BlockingQueue<AgentEvent> queue)
            throws InterruptedException {

        int unknownRun = 0;
        boolean loopCompleted = false;
        String earlyStop = null; // 非 null = 因取消/流错误提前退出，不能误报"迭代上限"

        try {
            int cap = maxIterations;
            for (int iter = 1; iter <= cap; iter++) {

                // ── check interrupt ──────────────────────────────────
                if (Thread.currentThread().isInterrupted()) {
                    earlyStop = "（已取消）";
                    break;
                }

                // ── hook: turn_start（注入提示 + 命令等动作）──
                final String userMsg = lastUserText(history);
                ChatMessage hookReminder = null;
                {
                    HookEngine he = hookEngine;
                    if (he != null) {
                        List<String> injected = he.runInjectHooks(new HookContext(
                                HookEvent.TURN_START, null, null, null, null, null));
                        if (!injected.isEmpty()) {
                            hookReminder = new ChatMessage(ChatMessage.Role.USER,
                                    "<system-reminder>\n" + String.join("\n", injected)
                                            + "\n</system-reminder>");
                            history.add(hookReminder);
                        }
                    }
                }

                // ── 第 11 章：每轮按当前白名单重算工具集（激活发生在循环中，
                // 每轮重算才能让收窄在当前轮内生效）。──
                client.setTools(computeSchemas(planMode));

                // ── 第 8 章：上下文管理。每次请求前先预防层存盘，再判断兜底压缩（F10）──
                String ctxStatus = contextManager.prepareBeforeRequest(history);
                if (!ctxStatus.isEmpty() && !emit(queue, new AgentEvent.Notice(ctxStatus))) {
                    earlyStop = "（已取消）";
                    break;
                }

                // ── plan mode: inject reminder per iteration (F6/F7) ──
                // 追加到本轮 history（user 角色，XML 包裹），仅在本次 LLM 调用期间可见：
                // streamOnce 返回后立即移除 —— 不写入跨轮持久历史，也不动 system prompt，
                // 稳定前缀保持字节不变才能命中缓存。
                ChatMessage planReminder = null;
                if (planMode) {
                    boolean planExists = Files.exists(Path.of(PLAN_PATH));
                    planReminder = new ChatMessage(ChatMessage.Role.USER,
                            "<system-reminder>\n"
                                    + PlanModePrompt.buildReminder(PLAN_PATH, planExists, iter)
                                    + "\n</system-reminder>");
                    history.add(planReminder);
                }

                // ── coordinator mode: inject dispatch reminder per iteration (F7/N5) ──
                // 与 planReminder 同款：仅本次 LLM 调用期间可见，调用后移除。
                ChatMessage coordinatorReminder = null;
                Supplier<Boolean> coordFn = coordinatorActiveFn;
                if (coordFn != null && Boolean.TRUE.equals(coordFn.get())) {
                    coordinatorReminder = new ChatMessage(ChatMessage.Role.USER,
                            "<system-reminder>\n"
                                    + CoordinatorPrompt.buildReminder(iter)
                                    + "\n</system-reminder>");
                    history.add(coordinatorReminder);
                }

                // ── emit progress ────────────────────────────────────
                if (!emit(queue, new AgentEvent.TurnComplete(iter))) {
                    earlyStop = "（已取消）";
                    break;
                }

                // ── hook: pre_send（发送前）──
                fireHook(HookEvent.PRE_SEND, null, null, null, userMsg, null);

                // ── call LLM + consume stream ────────────────────────
                StreamResult result;
                try {
                    result = streamOnce(history, systemPrompt, queue);
                } finally {
                    if (planReminder != null) history.remove(planReminder);
                    if (coordinatorReminder != null) history.remove(coordinatorReminder);
                    if (hookReminder != null) history.remove(hookReminder);
                }
                if (result == null) {
                    earlyStop = "（请求失败）";
                    break; // stream error
                }

                // ── hook: post_receive（接收后）──
                fireHook(HookEvent.POST_RECEIVE, null, null, null, result.text, null);

                // ── 第 8 章：锚定 token 估算（F9）──
                if (result.usageInput > 0) {
                    contextManager.onUsage(result.usageInput, history);
                }

                String text = result.text;
                List<StreamEvent.ToolCallComplete> calls = result.calls;

                // ── emit usage ───────────────────────────────────────
                if (result.usageInput > 0 || result.usageOutput > 0) {
                    if (!emit(queue, new AgentEvent.UsageEvent(
                            result.usageInput, result.usageOutput,
                            result.usageCacheRead, result.usageCacheWrite))) {
                        earlyStop = "（已取消）";
                        break;
                    }
                }

                // ── no tool calls → natural completion ───────────────
                if (calls.isEmpty()) {
                    String finalText = text.isEmpty() ? "（任务完成）" : text;
                    history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, finalText));
                    // ── hook: turn_end（本轮自然结束）──
                    fireHook(HookEvent.TURN_END, null, null, null, finalText, null);
                    emit(queue, new AgentEvent.LoopComplete(iter));
                    loopCompleted = true;
                    // 第 9 章：自然停下后异步沉淀记忆（回调内部快照 + 开虚拟线程，不阻塞）
                    Consumer<List<ChatMessage>> cb = onNaturalStop;
                    if (cb != null) {
                        cb.accept(history);
                    }
                    return;
                }

                // ── has tool calls → record in history ───────────────
                List<ChatMessage.ToolCall> tcList = new ArrayList<>();
                for (var c : calls) {
                    tcList.add(new ChatMessage.ToolCall(c.toolId(), c.toolName(), c.arguments()));
                }
                history.add(new ChatMessage(ChatMessage.Role.ASSISTANT,
                        text.isEmpty() ? "" : text, tcList));

                // ── check for unknown tools ──────────────────────────
                if (allUnknown(calls)) {
                    unknownRun++;
                } else {
                    unknownRun = 0;
                }

                // ── execute tools (batched) ───────────────────────────
                var execResult = executeBatched(calls, queue);
                for (var r : execResult.results) {
                    history.add(ChatMessage.toolResult(r.toolId, r.output));
                }

                // ── cancelled during execution ────────────────────────
                if (!execResult.completed) {
                    ensureAssistantTail(history, "（已取消）");
                    break;
                }

                // ── consecutive unknown tools stop ────────────────────
                if (unknownRun >= MAX_UNKNOWN_RUN) {
                    String msg = "（连续多轮只请求到未注册的工具，自动停止。）";
                    // ── hook: turn_end ──
                    fireHook(HookEvent.TURN_END, null, null, null, msg, null);
                    emit(queue, new AgentEvent.ErrorEvent(msg));
                    ensureAssistantTail(history, msg);
                    emit(queue, new AgentEvent.LoopComplete(iter));
                    loopCompleted = true;
                    return;
                }

                // ── hook: turn_end（本轮结束，继续下一轮）──
                fireHook(HookEvent.TURN_END, null, null, null, null, null);

                // ── continue to next iteration ────────────────────────
            }

            // 提前退出（取消/流错误）：真实原因已通过事件上报，这里只补齐历史尾部，
            // 不能误报"迭代上限"。
            if (earlyStop != null) {
                ensureAssistantTail(history, earlyStop);
                return;
            }

            // Hit iteration cap
            String msg = "（已达最大迭代轮数 " + maxIterations + "，自动停止；可继续发消息推进。）";
            emit(queue, new AgentEvent.ErrorEvent(msg));
            ensureAssistantTail(history, msg);

        } finally {
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
        }
    }

    // ── Stream Once ──────────────────────────────────────────────────────

    /** 计算本轮工具 schema：plan → 只读；白名单空 → 全量；否则按白名单过滤；
     *  再叠加工具名过滤器（第 15 章 coordinator 收窄）。 */
    private List<Map<String, Object>> computeSchemas(boolean planMode) {
        Set<String> wl = toolWhitelist;
        boolean hasWl = wl != null && !wl.isEmpty();
        List<Map<String, Object>> schemas = planMode
                ? (hasWl ? registry.getReadOnlySchemas(protocol, wl) : registry.getReadOnlySchemas(protocol))
                : (hasWl ? registry.getAllSchemas(protocol, wl) : registry.getAllSchemas(protocol));
        Predicate<String> f = toolNameFilter;
        if (f != null) {
            schemas = schemas.stream().filter(s -> schemaNameMatches(s, f)).toList();
        }
        return schemas;
    }

    /** schema 元素的工具名提取（anthropic 直接 name；openai 嵌套于 function.name）。 */
    private static boolean schemaNameMatches(Map<String, Object> schema, Predicate<String> filter) {
        Object name = schema.get("name");
        if (name instanceof String s) return filter.test(s);
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fm) {
            Object fname = fm.get("name");
            if (fname instanceof String s) return filter.test(s);
        }
        return true; // 未知形态：保守保留
    }

    private static class StreamResult {
        String text = "";
        List<StreamEvent.ToolCallComplete> calls = new ArrayList<>();
        int usageInput = 0, usageOutput = 0;
        int usageCacheRead = 0, usageCacheWrite = 0;
    }

    private StreamResult streamOnce(List<ChatMessage> history, String systemPrompt,
                                    BlockingQueue<AgentEvent> queue)
            throws InterruptedException {
        var result = new StreamResult();
        var streamQueue = client.stream(history, systemPrompt);

        while (true) {
            StreamEvent evt;
            try {
                evt = streamQueue.poll(90, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (evt == null) {
                // timeout — treat as error
                emit(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                return null;
            }

            switch (evt) {
                case StreamEvent.TextDelta td -> {
                    result.text += td.text();
                    if (!emit(queue, new AgentEvent.StreamText(td.text()))) return null;
                }
                case StreamEvent.ToolCallStart ts -> { /* accumulated in client */ }
                case StreamEvent.ToolCallDelta td -> { /* accumulated in client */ }
                case StreamEvent.ToolCallComplete tc -> result.calls.add(tc);
                case StreamEvent.Usage u -> {
                    result.usageInput = u.inputTokens();
                    result.usageOutput = u.outputTokens();
                    result.usageCacheRead = u.cacheRead();
                    result.usageCacheWrite = u.cacheWrite();
                }
                case StreamEvent.StreamEnd se -> {
                    return result;
                }
                case StreamEvent.Error err -> {
                    emit(queue, new AgentEvent.ErrorEvent(err.message()));
                    return null;
                }
                case StreamEvent.ThinkingDelta ignored -> {}
            }
        }
    }

    // ── Batched Execution ────────────────────────────────────────────────

    private static class ExecResult {
        List<ToolExecItem> results;
        boolean completed;
        ExecResult(List<ToolExecItem> r, boolean c) { results = r; completed = c; }
    }

    private record ToolExecItem(String toolId, String output, boolean isError) {}

    private ExecResult executeBatched(List<StreamEvent.ToolCallComplete> calls,
                                      BlockingQueue<AgentEvent> queue)
            throws InterruptedException {

        List<ToolExecItem> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); ) {
            StreamEvent.ToolCallComplete call = calls.get(i);
            Tool tool = registry.get(call.toolName());
            boolean isRead = tool != null && tool.category() == ToolCategory.READ;

            if (isRead) {
                // Gather consecutive READ calls
                int j = i;
                while (j < calls.size()) {
                    Tool tj = registry.get(calls.get(j).toolName());
                    if (tj == null || tj.category() != ToolCategory.READ) break;
                    j++;
                }
                List<StreamEvent.ToolCallComplete> batch = calls.subList(i, j);

                // Permission pre-check — READ never reaches Ask, so this is fast and
                // stays off the concurrent path (N3: reads are not serialized).
                var batchResults = new ToolExecItem[batch.size()];
                var decisions = new Decision[batch.size()];
                var hookReject = new String[batch.size()];
                for (int k = 0; k < batch.size(); k++) {
                    var c = batch.get(k);
                    decisions[k] = permissionEngine.decide(
                            registry.get(c.toolName()), c.arguments());
                    if (decisions[k].verdict() == Decision.Verdict.DENY) {
                        batchResults[k] = new ToolExecItem(c.toolId(),
                                "Error: 权限拒绝: " + decisions[k].reason(), true);
                        continue;
                    }
                    // hook: pre_tool_use 拦截（第 12 章 F5）
                    hookReject[k] = hookPreCheck(c.toolName(), c.arguments());
                    if (hookReject[k] != null) {
                        batchResults[k] = new ToolExecItem(c.toolId(), hookReject[k], true);
                    }
                }

                // Emit ToolUseEvent for allowed calls only (in order); denied reads
                // go straight to their error ToolResultEvent, never "Running…".
                for (int k = 0; k < batch.size(); k++) {
                    var c = batch.get(k);
                    if (decisions[k].verdict() == Decision.Verdict.DENY) continue;
                    if (hookReject[k] != null) continue;
                    if (registry.get(c.toolName()) == null) continue;
                    if (!emit(queue, new AgentEvent.ToolUseEvent(
                            c.toolId(), c.toolName(), c.arguments()))) {
                        // Cancelled — fill remaining with cancel markers
                        for (var remaining : calls.subList(calls.indexOf(c), calls.size())) {
                            results.add(new ToolExecItem(remaining.toolId(), "（已取消）", true));
                        }
                        return new ExecResult(results, false);
                    }
                }

                // Concurrent execution of allowed calls
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = new ArrayList<Future<?>>();
                    for (int k = 0; k < batch.size(); k++) {
                        final int idx = k;
                        final var c = batch.get(k);
                        if (decisions[idx].verdict() == Decision.Verdict.DENY) continue;
                        if (hookReject[idx] != null) continue;
                        futures.add(executor.submit(() -> {
                            if (Thread.currentThread().isInterrupted()) {
                                batchResults[idx] = new ToolExecItem(c.toolId(), "（已取消）", true);
                                return;
                            }
                            Tool t = registry.get(c.toolName());
                            long start = System.nanoTime();
                            ToolResult tr;
                            if (t == null) {
                                tr = ToolResult.error("Error: unknown tool '" + c.toolName() + "'");
                            } else {
                                try {
                                    tr = t.execute(c.arguments());
                                } catch (Exception e) {
                                    tr = ToolResult.error("Tool execution error: " + e.getMessage());
                                }
                            }
                            double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
                            String output = tr.output();
                            if (tr.isError()) output = "Error: " + output;
                            batchResults[idx] = new ToolExecItem(c.toolId(), output, tr.isError());
                            putSafe(queue, new AgentEvent.ToolResultEvent(
                                    c.toolId(), c.toolName(), output, tr.isError(), elapsed));
                            // hook: post_tool_use（工具实际执行后）
                            if (t != null) hookPostFire(c.toolName(), c.arguments(), output, tr.isError());
                        }));
                    }
                    for (var f : futures) {
                        try { f.get(); } catch (Exception e) { /* logged by tool */ }
                    }
                }

                for (int k = 0; k < batch.size(); k++) {
                    var r = batchResults[k];
                    if (r == null) {
                        results.add(new ToolExecItem(batch.get(k).toolId(), "执行失败", true));
                        continue;
                    }
                    results.add(r);
                    // 被拒/被 hook 拦截的读调用：补发错误事件，让 UI 可见（历史配对按 toolId，不变）
                    if (decisions[k].verdict() == Decision.Verdict.DENY || hookReject[k] != null) {
                        putSafe(queue, new AgentEvent.ToolResultEvent(
                                batch.get(k).toolId(), batch.get(k).toolName(),
                                r.output(), true, 0));
                    }
                }
                i = j;
            } else {
                // Single serial execution (WRITE or COMMAND) — permission gate first
                Decision decision;
                try {
                    decision = permissionEngine.decide(tool, call.arguments());
                } catch (PermissionCancelledException e) {
                    // HITL cancelled — fill remaining, end the turn cleanly (N4)
                    for (var remaining : calls.subList(i, calls.size())) {
                        results.add(new ToolExecItem(remaining.toolId(), "（已取消）", true));
                    }
                    return new ExecResult(results, false);
                }

                if (decision.verdict() == Decision.Verdict.DENY) {
                    String output = "Error: 权限拒绝: " + decision.reason();
                    results.add(new ToolExecItem(call.toolId(), output, true));
                    putSafe(queue, new AgentEvent.ToolResultEvent(
                            call.toolId(), call.toolName(), output, true, 0));
                    i++;
                    continue;
                }

                // hook: pre_tool_use 拦截（第 12 章 F5）
                String hookRejectOut = hookPreCheck(call.toolName(), call.arguments());
                if (hookRejectOut != null) {
                    results.add(new ToolExecItem(call.toolId(), hookRejectOut, true));
                    putSafe(queue, new AgentEvent.ToolResultEvent(
                            call.toolId(), call.toolName(), hookRejectOut, true, 0));
                    i++;
                    continue;
                }

                if (!emit(queue, new AgentEvent.ToolUseEvent(
                        call.toolId(), call.toolName(), call.arguments()))) {
                    // Cancelled — fill remaining
                    for (var remaining : calls.subList(i, calls.size())) {
                        results.add(new ToolExecItem(remaining.toolId(), "（已取消）", true));
                    }
                    return new ExecResult(results, false);
                }

                Tool t = registry.get(call.toolName());
                long start = System.nanoTime();
                ToolResult tr;
                if (t == null) {
                    tr = ToolResult.error("Error: unknown tool '" + call.toolName() + "'");
                } else {
                    try {
                        tr = t.execute(call.arguments());
                    } catch (Exception e) {
                        tr = ToolResult.error("Tool execution error: " + e.getMessage());
                    }
                }
                double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
                String output = tr.output();
                if (tr.isError()) output = "Error: " + output;
                results.add(new ToolExecItem(call.toolId(), output, tr.isError()));
                putSafe(queue, new AgentEvent.ToolResultEvent(
                        call.toolId(), call.toolName(), output, tr.isError(), elapsed));
                // hook: post_tool_use（工具实际执行后）
                if (t != null) hookPostFire(call.toolName(), call.arguments(), output, tr.isError());
                i++;
            }
        }
        return new ExecResult(results, true);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Check if every tool call in the list is for an unregistered tool. */
    private boolean allUnknown(List<StreamEvent.ToolCallComplete> calls) {
        if (calls.isEmpty()) return false;
        for (var c : calls) {
            if (registry.get(c.toolName()) != null) return false;
        }
        return true;
    }

    // ── Hook 触发（第 12 章）──────────────────────────────────────────────

    /** 触发非拦截类事件；hookEngine 为 null 时跳过。 */
    private void fireHook(HookEvent event, String toolName, Map<String, Object> args,
                          String filePath, String message, String error) {
        HookEngine he = hookEngine;
        if (he == null) return;
        he.runHooks(new HookContext(event, toolName, args, filePath, message, error));
    }

    /** pre_tool_use 拦截检查；命中返回拒绝输出，否则 null。 */
    private String hookPreCheck(String toolName, Map<String, Object> args) {
        HookEngine he = hookEngine;
        if (he == null) return null;
        PreToolResult pre = he.runPreToolHooks(toolName, args);
        return pre.rejected() ? ("Error: Hook 拦截: " + pre.message()) : null;
    }

    /** post_tool_use 触发（工具实际执行后）。 */
    private void hookPostFire(String toolName, Map<String, Object> args, String output, boolean isError) {
        HookEngine he = hookEngine;
        if (he == null) return;
        he.runHooks(new HookContext(HookEvent.POST_TOOL_USE, toolName, args,
                HookContext.filePathOf(args), output, isError ? "error" : null));
    }

    /** 取最近一条用户消息文本（用于 pre_send 的 message 字段）。 */
    private static String lastUserText(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.getRole() == ChatMessage.Role.USER && !m.hasToolCalls()) {
                return m.getContent();
            }
        }
        return null;
    }

    /** Make sure history ends with an assistant text message. */
    private void ensureAssistantTail(List<ChatMessage> history, String fallback) {
        if (history.isEmpty()) {
            history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, fallback));
            return;
        }
        var last = history.get(history.size() - 1);
        if (last.getRole() != ChatMessage.Role.ASSISTANT || last.hasToolCalls()) {
            history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, fallback));
        }
    }

    /** Put event into queue, ignoring interrupt; returns false if safe-put wasn't needed. */
    private static boolean emit(BlockingQueue<AgentEvent> queue, AgentEvent event)
            throws InterruptedException {
        try {
            queue.put(event);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Put without throwing (for finally blocks and worker threads). */
    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try { queue.put(event); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
