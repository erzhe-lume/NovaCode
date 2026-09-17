package com.novacode.subagent;

import com.novacode.agent.Agent;
import com.novacode.agent.AgentEvent;
import com.novacode.config.ProviderConfig;
import com.novacode.context.ContextManager;
import com.novacode.model.ChatMessage;
import com.novacode.permission.PathSandbox;
import com.novacode.permission.PermissionEngine;
import com.novacode.permission.PermissionMode;
import com.novacode.protocol.LlmClient;
import com.novacode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 子 Agent 共享编排（第 13 章）：构造隔离的子 Agent + 跑到底 + 排空事件。
 *
 * <p>「跑到底」语义复用 NovaCode {@link Agent}（模型不再请求工具即 LoopComplete）。
 * 状态隔离：每次 {@link #buildAgent} 都新建 PermissionEngine（{@link PermissionEngine#forSubAgent}，
 * 无人在回路）与全新 {@link ContextManager}（token 计数隔离）；文件读缓存隔离由
 * {@link ToolFilter} 重建工具实例保证。</p>
 */
public final class SubAgentRunner {

    /** 一次子 Agent 运行的结果。 */
    public record Outcome(String output, String error, boolean ok,
                          int toolCount, double seconds,
                          int inputTokens, int outputTokens) {}

    /** 排空结果：完成/失败，或超时后交给后台续跑。 */
    public sealed interface RunResult permits RunResult.Done, RunResult.Detached {
        Outcome outcome();
        record Done(Outcome outcome) implements RunResult {}
        record Detached(Continuation continuation, Outcome partial) implements RunResult {
            @Override public Outcome outcome() { return partial; }
        }
    }

    private final LlmClient parentClient;
    private final String protocol;
    private final ProviderConfig providerConfig;
    private final PermissionEngine parentPermissionEngine;

    private Function<String, LlmClient> modelResolver;
    private Consumer<SubAgentProgress> progressListener;

    public SubAgentRunner(LlmClient parentClient, String protocol, ProviderConfig providerConfig,
                          PermissionEngine parentPermissionEngine) {
        this.parentClient = parentClient;
        this.protocol = protocol;
        this.providerConfig = providerConfig;
        this.parentPermissionEngine = parentPermissionEngine;
    }

    public void setModelResolver(Function<String, LlmClient> resolver) { this.modelResolver = resolver; }
    public void setProgressListener(Consumer<SubAgentProgress> listener) { this.progressListener = listener; }

    /** 解析模型别名到 LlmClient；override 优先，其次 spec.model，null/空/"inherit" → 父 client。 */
    public LlmClient selectClient(String specModel, String overrideModel) {
        String model = (overrideModel != null && !overrideModel.isEmpty()) ? overrideModel : specModel;
        if (model == null || model.isEmpty() || "inherit".equals(model)) return parentClient;
        if (modelResolver != null) {
            LlmClient resolved = modelResolver.apply(model);
            if (resolved != null) return resolved;
        }
        return parentClient;
    }

    /** 构造隔离的子 Agent：forSubAgent 权限 + 全新 ContextManager + 可选 maxTurns。 */
    public Agent buildAgent(ToolRegistry registry, LlmClient client, PermissionMode mode, int maxTurns) {
        return buildAgent(registry, client, mode, maxTurns, null);
    }

    /** 同 {@link #buildAgent}，sandboxRoot 非空时用其重建沙箱（第 14 章 worktree 隔离）。 */
    public Agent buildAgent(ToolRegistry registry, LlmClient client, PermissionMode mode, int maxTurns, Path sandboxRoot) {
        PermissionEngine subPerm = parentPermissionEngine.forSubAgent(() -> mode);
        if (sandboxRoot != null) subPerm = subPerm.withSandbox(new PathSandbox(sandboxRoot));
        Agent agent = new Agent(client, registry, protocol, subPerm, new ContextManager(providerConfig));
        if (maxTurns > 0) agent.policy().setMaxIterations(maxTurns);
        return agent;
    }

    /** 队员 Agent 构造助手（第 15 章）：与 {@link #buildAgent} 同语义（forSubAgent 权限 +
     *  可选 worktree 沙箱），命名分离以便调用方表达意图。 */
    public Agent buildTeammateAgent(ToolRegistry registry, LlmClient client, PermissionMode mode,
                                    int maxTurns, Path sandboxRoot) {
        return buildAgent(registry, client, mode, maxTurns, sandboxRoot);
    }

    /** 启动子 Agent 并排空到结束/失败/超时（超时可交后台续跑）。 */
    public RunResult run(Agent agent, List<ChatMessage> history, String systemPrompt, boolean planMode,
                         String description, String specName, long timeoutSeconds, boolean detachOnTimeout) {
        return drain(agent.run(history, systemPrompt, planMode), description, specName,
                timeoutSeconds, detachOnTimeout);
    }

    /** 核心排空循环（可直接对队列测试）。 */
    public RunResult drain(BlockingQueue<AgentEvent> queue, String description, String specName,
                           long timeoutSeconds, boolean detachOnTimeout) {
        var acc = new Accumulator(description, specName);
        long deadline = timeoutSeconds > 0 ? System.nanoTime() + timeoutSeconds * 1_000_000_000L : 0;

        while (true) {
            AgentEvent evt;
            try {
                evt = queue.poll(pollWaitMs(deadline), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new RunResult.Done(acc.failed("Interrupted"));
            }
            if (evt == null) {
                if (deadline > 0 && System.nanoTime() >= deadline) {
                    if (detachOnTimeout) {
                        return new RunResult.Detached(new Continuation(queue, acc), acc.timedOut());
                    }
                    return new RunResult.Done(acc.failed("timeout after " + timeoutSeconds + "s"));
                }
                return new RunResult.Done(acc.failed("timeout waiting for events"));
            }
            switch (evt) {
                case AgentEvent.StreamText st -> acc.append(st.text());
                case AgentEvent.ToolResultEvent tre -> {
                    acc.toolCount++;
                    emitProgress(acc, specName, tre.toolName(), tre.output(), tre.isError(), false);
                }
                case AgentEvent.UsageEvent ue -> {
                    acc.inTok += ue.inputTokens();
                    acc.outTok += ue.outputTokens();
                }
                case AgentEvent.ErrorEvent err -> {
                    return new RunResult.Done(acc.failed(err.message()));
                }
                case AgentEvent.LoopComplete lc -> {
                    emitProgress(acc, specName, null, null, false, true);
                    return new RunResult.Done(acc.completed());
                }
                default -> {}
            }
        }
    }

    /** 单次 poll 等待时长：受看门狗剩余时间约束（否则小 timeout 无法及时触发）。 */
    private static long pollWaitMs(long deadline) {
        if (deadline <= 0) return 120_000L;
        long remain = (deadline - System.nanoTime()) / 1_000_000L;
        return Math.max(1, Math.min(120_000L, remain));
    }

    private void emitProgress(Accumulator acc, String specName, String toolName,
                              String toolOutput, boolean toolError, boolean done) {
        if (progressListener != null) {
            progressListener.accept(new SubAgentProgress(specName, acc.description, toolName, toolOutput,
                    toolError, done, acc.toolCount, acc.seconds()));
        }
    }

    /** 超时续跑单元：续排空同一队列直到结束/失败。 */
    public final class Continuation {
        private final BlockingQueue<AgentEvent> queue;
        private final Accumulator acc;
        Continuation(BlockingQueue<AgentEvent> queue, Accumulator acc) { this.queue = queue; this.acc = acc; }

        public Outcome finish() {
            while (true) {
                AgentEvent evt;
                try {
                    evt = queue.poll(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return acc.failed("Interrupted");
                }
                if (evt == null) return acc.failed("timeout waiting for events");
                switch (evt) {
                    case AgentEvent.StreamText st -> acc.append(st.text());
                    case AgentEvent.ToolResultEvent tre -> acc.toolCount++;
                    case AgentEvent.UsageEvent ue -> {
                        acc.inTok += ue.inputTokens();
                        acc.outTok += ue.outputTokens();
                    }
                    case AgentEvent.ErrorEvent err -> { return acc.failed(err.message()); }
                    case AgentEvent.LoopComplete lc -> { return acc.completed(); }
                    default -> {}
                }
            }
        }
    }

    /** 累积状态（前台与续跑共享）。 */
    private static final class Accumulator {
        final String description;
        final long startNanos = System.nanoTime();
        final StringBuilder output = new StringBuilder();
        int toolCount = 0;
        int inTok = 0, outTok = 0;

        Accumulator(String description, String specName) {
            this.description = description;
        }

        void append(String s) { output.append(s); }
        double seconds() { return (System.nanoTime() - startNanos) / 1_000_000_000.0; }
        Outcome completed() {
            String o = output.isEmpty() ? "(agent produced no output)" : output.toString();
            return new Outcome(o, null, true, toolCount, seconds(), inTok, outTok);
        }
        Outcome failed(String err) {
            return new Outcome(output.toString(), err, false, toolCount, seconds(), inTok, outTok);
        }
        Outcome timedOut() {
            return new Outcome(output.toString(), null, false, toolCount, seconds(), inTok, outTok);
        }
    }
}
