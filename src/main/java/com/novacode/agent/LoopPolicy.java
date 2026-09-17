package com.novacode.agent;

import com.novacode.hook.HookEngine;
import com.novacode.model.ChatMessage;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Agent 循环的行为参数集（原先是散在 Agent 上的六组 volatile 字段 + setter）。
 *
 * <p>通过 {@code agent.policy()} 拿到实例后直接改字段。所有字段保持 volatile：
 * 技能激活 / coordinator 收窄可能发生在循环运行中，策略在每轮迭代开头重读，
 * 修改当轮即生效。</p>
 */
public final class LoopPolicy {

    /** 迭代上限默认值；非正的 setMaxIterations 复位到此。 */
    public static final int DEFAULT_MAX_ITERATIONS = 50;

    /** 自然结束回调：模型最终回复无工具调用时触发，用于异步沉淀记忆；null = 无。 */
    private volatile Consumer<List<ChatMessage>> onNaturalStop;

    /** 工具白名单：空集 = 不收窄（全工具）；非空 = 仅暴露名单内工具。 */
    private volatile Set<String> toolWhitelist = Set.of();

    /** 工具名过滤器（coordinator 收窄）：null = 不过滤；命中才保留。 */
    private volatile Predicate<String> toolNameFilter;

    /** coordinator 激活判定：null = 不注入调度指引。 */
    private volatile Supplier<Boolean> coordinatorActiveFn;

    /** 循环迭代上限。 */
    private volatile int maxIterations = DEFAULT_MAX_ITERATIONS;

    /** Hook 引擎：null 时所有 hook 逻辑跳过。 */
    private volatile HookEngine hookEngine;

    public Consumer<List<ChatMessage>> getOnNaturalStop() { return onNaturalStop; }

    public void setOnNaturalStop(Consumer<List<ChatMessage>> callback) {
        this.onNaturalStop = callback;
    }

    public Set<String> getToolWhitelist() { return toolWhitelist; }

    /** 空集/null 复位为全工具。 */
    public void setToolWhitelist(Set<String> whitelist) {
        this.toolWhitelist = whitelist == null ? Set.of() : whitelist;
    }

    public Predicate<String> getToolNameFilter() { return toolNameFilter; }

    /** null 复位为不过滤。 */
    public void setToolNameFilter(Predicate<String> filter) {
        this.toolNameFilter = filter;
    }

    public Supplier<Boolean> getCoordinatorActiveFn() { return coordinatorActiveFn; }

    /** null 复位为不注入指引。 */
    public void setCoordinatorActiveFn(Supplier<Boolean> fn) {
        this.coordinatorActiveFn = fn;
    }

    public int getMaxIterations() { return maxIterations; }

    /** 非正值复位为默认上限。 */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations > 0 ? maxIterations : DEFAULT_MAX_ITERATIONS;
    }

    public HookEngine getHookEngine() { return hookEngine; }

    /** null 关闭 hook。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }
}
