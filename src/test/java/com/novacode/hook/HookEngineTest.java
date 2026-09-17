package com.novacode.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Hook 引擎：PROMPT 注入、pre_tool_use 拦截、once 语义、配置校验。 */
class HookEngineTest {

    @Test
    void promptHookInjectsOnTurnStart() {
        var engine = new HookEngine();
        engine.addHook(new Hook("h1", HookEvent.TURN_START, null,
                new HookAction(HookActionType.PROMPT, null, "注入正文"), false, false, false));

        List<String> injected = engine.runInjectHooks(
                new HookContext(HookEvent.TURN_START, null, null, null, null, null));

        assertEquals(List.of("注入正文"), injected);
    }

    @Test
    void otherEventsDoNotCrossFire() {
        var engine = new HookEngine();
        engine.addHook(new Hook("h1", HookEvent.TURN_START, null,
                new HookAction(HookActionType.PROMPT, null, "注入正文"), false, false, false));

        List<String> injected = engine.runInjectHooks(
                new HookContext(HookEvent.SESSION_START, null, null, null, null, null));
        assertTrue(injected.isEmpty(), "事件不匹配不应注入");
    }

    @Test
    void preToolHookRejects() {
        var engine = new HookEngine();
        engine.addHook(new Hook("guard", HookEvent.PRE_TOOL_USE, null,
                new HookAction(HookActionType.PROMPT, null, "禁止执行"), true, false, false));

        PreToolResult result = engine.runPreToolHooks("Bash", Map.of("command", "rm -rf /"));

        assertTrue(result.rejected());
        assertEquals("禁止执行", result.message());
    }

    @Test
    void nonRejectPreToolHookPasses() {
        var engine = new HookEngine();
        engine.addHook(new Hook("log", HookEvent.PRE_TOOL_USE, null,
                new HookAction(HookActionType.PROMPT, null, "记录"), false, false, false));

        PreToolResult result = engine.runPreToolHooks("Bash", Map.of("command", "ls"));
        assertFalse(result.rejected());
    }

    @Test
    void onceHookFiresOnlyOnce() {
        var engine = new HookEngine();
        engine.addHook(new Hook("once-1", HookEvent.TURN_START, null,
                new HookAction(HookActionType.PROMPT, null, "一次性提示"), false, true, false));

        assertEquals(1, engine.runInjectHooks(
                new HookContext(HookEvent.TURN_START, null, null, null, null, null)).size());
        assertTrue(engine.runInjectHooks(
                new HookContext(HookEvent.TURN_START, null, null, null, null, null)).isEmpty(),
                "once=true 第二次不应再注入");
    }

    @Test
    void validateFlagsMissingMessageForPromptAction() {
        var bad = new Hook("bad", HookEvent.TURN_START, null,
                new HookAction(HookActionType.PROMPT, null, null), false, false, false);
        assertFalse(HookEngine.validate(List.of(bad)).isEmpty(), "PROMPT 缺 message 应报校验错误");
    }

    @Test
    void validateFlagsAsyncPreToolUse() {
        var bad = new Hook("bad", HookEvent.PRE_TOOL_USE, null,
                new HookAction(HookActionType.PROMPT, null, "msg"), true, false, true);
        assertFalse(HookEngine.validate(List.of(bad)).isEmpty(), "pre_tool_use 不允许 async");
    }

    @Test
    void validateAcceptsWellFormedHook() {
        var good = new Hook("ok", HookEvent.TURN_START, null,
                new HookAction(HookActionType.PROMPT, null, "msg"), false, false, false);
        assertTrue(HookEngine.validate(List.of(good)).isEmpty());
    }
}
