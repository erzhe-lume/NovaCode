package com.novacode.agent;

import com.novacode.config.ProviderConfig;
import com.novacode.context.ContextManager;
import com.novacode.hook.Hook;
import com.novacode.hook.HookAction;
import com.novacode.hook.HookActionType;
import com.novacode.hook.HookEngine;
import com.novacode.hook.HookEvent;
import com.novacode.model.ChatMessage;
import com.novacode.permission.Blacklist;
import com.novacode.permission.PathSandbox;
import com.novacode.permission.PermissionEngine;
import com.novacode.permission.PermissionMode;
import com.novacode.permission.RuleEngine;
import com.novacode.protocol.LlmClient;
import com.novacode.protocol.StreamEvent;
import com.novacode.tool.ToolRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 循环端到端测试：用脚本化 FakeClient 驱动真实循环（不加网络）。
 * 覆盖：自然停止、工具执行回流、权限拒绝回流、迭代上限、未知工具熔断、
 * plan mode 工具收窄、hook 注入与清理。
 */
class AgentLoopTest {

    @TempDir
    static Path tempDir;

    private static String realUserDir;

    @BeforeAll
    static void redirectUserDir() {
        realUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterAll
    static void restoreUserDir() {
        System.setProperty("user.dir", realUserDir);
    }

    // ── FakeClient：按脚本回放响应，记录请求 ──────────────────────────

    private record Scripted(String text, List<StreamEvent.ToolCallComplete> calls) {
        static Scripted text(String t) { return new Scripted(t, List.of()); }
        static Scripted call(String id, String name, Map<String, Object> args) {
            return new Scripted("", List.of(new StreamEvent.ToolCallComplete(id, name, args)));
        }
    }

    private static final class FakeClient implements LlmClient {
        private final java.util.Deque<Scripted> script = new java.util.ArrayDeque<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();
        final List<List<Map<String, Object>>> toolsHistory = new ArrayList<>();

        FakeClient(Scripted... responses) {
            for (Scripted s : responses) script.add(s);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(List<ChatMessage> history, String systemPrompt) {
            requests.add(new ArrayList<>(history));
            var q = new LinkedBlockingQueue<StreamEvent>();
            Scripted s = script.poll();
            if (s == null) {
                q.add(new StreamEvent.Error("script exhausted"));
                q.add(new StreamEvent.StreamEnd("end"));
                return q;
            }
            if (!s.text().isEmpty()) q.add(new StreamEvent.TextDelta(s.text()));
            q.addAll(s.calls());
            q.add(new StreamEvent.Usage(100, 10, 50, 0));
            q.add(new StreamEvent.StreamEnd(s.calls().isEmpty() ? "end_turn" : "tool_calls"));
            return q;
        }

        @Override
        public void setTools(List<Map<String, Object>> tools) {
            toolsHistory.add(tools);
        }
    }

    private record RunResult(List<AgentEvent> events, List<ChatMessage> history) {
        String historyText() {
            var sb = new StringBuilder();
            for (ChatMessage m : history) sb.append(m.getRole()).append(':')
                    .append(m.getContent() == null ? "" : m.getContent()).append('\n');
            return sb.toString();
        }
    }

    private RunResult runLoop(FakeClient client, boolean planMode) throws Exception {
        return runLoop(client, planMode, null);
    }

    private RunResult runLoop(FakeClient client, boolean planMode, HookEngine hooks) throws Exception {
        ToolRegistry registry = ToolRegistry.createDefault();
        var engine = new PermissionEngine(new Blacklist(), new PathSandbox(tempDir),
                new RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        cfg.setContextWindow(64_000);
        var cm = new ContextManager(cfg);
        Agent agent = new Agent(client, registry, "openai-compat", engine, cm);
        agent.setHookEngine(hooks);

        var history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "开始任务"));
        BlockingQueue<AgentEvent> q = agent.run(history, "sys", planMode);

        var events = new ArrayList<AgentEvent>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = q.poll(100, TimeUnit.MILLISECONDS);
            if (e == null) continue;
            events.add(e);
            if (e instanceof AgentEvent.LoopComplete) return new RunResult(events, history);
        }
        fail("Agent 循环 15 秒内未结束");
        return new RunResult(events, history);
    }

    // ── 场景 ─────────────────────────────────────────────────────────

    @Test
    void naturalStopEndsLoopWithAssistantTail() throws Exception {
        var client = new FakeClient(Scripted.text("任务完成"));
        RunResult result = runLoop(client, false);

        assertTrue(result.events().stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));
        assertTrue(client.requests.size() == 1, "无工具调用应一轮结束");
        var last = result.history().get(result.history().size() - 1);
        assertEquals(ChatMessage.Role.ASSISTANT, last.getRole());
        assertEquals("任务完成", last.getContent());
    }

    @Test
    void toolResultFeedsBackIntoNextRequest() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello content");
        var client = new FakeClient(
                Scripted.call("c1", "ReadFile", Map.of("file_path", "hello.txt")),
                Scripted.text("已读取"));

        RunResult result = runLoop(client, false);

        assertTrue(client.requests.size() == 2, "工具调用应产生第二次请求");
        // 第二次请求的历史里应包含工具结果
        String secondRequest = client.requests.get(1).stream()
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(secondRequest.contains("hello content"), "工具结果应回流进下一轮请求");
        // 最终历史以 assistant 文本收尾
        var last = result.history().get(result.history().size() - 1);
        assertEquals("已读取", last.getContent());
    }

    @Test
    void deniedWriteFeedsErrorBackAndLoopContinues() throws Exception {
        var client = new FakeClient(
                Scripted.call("c1", "WriteFile", Map.of("file_path", "out.txt", "content", "x")),
                Scripted.text("收到"));

        RunResult result = runLoop(client, false);

        // DEFAULT 模式 + 无确认通道 → 写拒绝 → 错误回流给模型
        assertTrue(result.historyText().contains("权限拒绝"),
                "拒绝原因应作为工具结果回流: " + result.historyText());
        assertTrue(client.requests.size() == 2, "拒绝后循环应继续而非中断");
        assertFalse(Files.exists(tempDir.resolve("out.txt")), "被拒的写不应执行");
    }

    @Test
    void iterationCapStopsLoop() throws Exception {
        var call = Scripted.call("c", "Bash", Map.of("command", "echo hi"));
        var client = new FakeClient(call, call, call, call, call);
        ToolRegistry registry = ToolRegistry.createDefault();
        var engine = new PermissionEngine(new Blacklist(), new PathSandbox(tempDir),
                new RuleEngine(), () -> PermissionMode.BYPASS_PERMISSIONS, null, null);
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        cfg.setContextWindow(64_000);
        var cm = new ContextManager(cfg);
        Agent agent = new Agent(client, registry, "openai-compat", engine, cm);
        agent.setMaxIterations(2);

        var history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "loop"));
        BlockingQueue<AgentEvent> q = agent.run(history, "sys", false);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = q.poll(100, TimeUnit.MILLISECONDS);
            if (e instanceof AgentEvent.LoopComplete) break;
        }
        String text = history.stream().map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("已达最大迭代轮数 2"), "应因迭代上限停止: " + text);
        assertTrue(client.requests.size() == 2, "恰好跑满 2 轮");
    }

    @Test
    void unknownToolRunStopsAfterThree() throws Exception {
        var call = Scripted.call("c", "NoSuchTool", Map.of());
        var client = new FakeClient(call, call, call);

        RunResult result = runLoop(client, false);

        assertTrue(result.historyText().contains("未注册的工具"),
                "连续未知工具应熔断停止: " + result.historyText());
        assertTrue(client.requests.size() == 3, "3 次未知工具调用后停止");
    }

    @Test
    void planModeRestrictsToolsToReadOnly() throws Exception {
        var client = new FakeClient(Scripted.text("计划好了"));

        runLoop(client, true);

        assertFalse(client.toolsHistory.isEmpty(), "循环应设置工具集");
        List<Map<String, Object>> last = client.toolsHistory.get(client.toolsHistory.size() - 1);
        // openai-compat 协议下工具名嵌在 function.name；anthropic 在顶层 name
        List<String> names = last.stream().map(s -> {
            Object n = s.get("name");
            if (n instanceof String str) return str;
            if (s.get("function") instanceof Map<?, ?> f) return String.valueOf(f.get("name"));
            return "";
        }).toList();
        assertTrue(names.contains("ReadFile"), "plan 模式应有只读工具");
        assertTrue(names.contains("TodoWrite"), "plan 模式应有任务清单");
        assertFalse(names.contains("WriteFile"), "plan 模式不应有写工具");
        assertFalse(names.contains("Bash"), "plan 模式不应有命令工具");
    }

    @Test
    void parallelReadBatchProducesAllResultsInOrder() throws Exception {
        // 一次响应带两个 ReadFile 调用 → 并发批次，结果都回流且按 toolId 对应
        Files.writeString(tempDir.resolve("a.txt"), "AAA");
        Files.writeString(tempDir.resolve("b.txt"), "BBB");
        var client = new FakeClient(
                new Scripted("", List.of(
                        new StreamEvent.ToolCallComplete("c1", "ReadFile", Map.of("file_path", "a.txt")),
                        new StreamEvent.ToolCallComplete("c2", "ReadFile", Map.of("file_path", "b.txt")))),
                Scripted.text("两个都读完了"));

        RunResult result = runLoop(client, false);

        assertTrue(client.requests.size() == 2);
        String historyText = result.historyText();
        assertTrue(historyText.contains("AAA"), "a.txt 结果应回流: " + historyText);
        assertTrue(historyText.contains("BBB"), "b.txt 结果应回流");
        // 每个 tool_call 都有对应 tool_result（2 对配平）
        long toolResults = result.history().stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL).count();
        assertEquals(2, toolResults, "并行批次应产生两条工具结果");
        var last = result.history().get(result.history().size() - 1);
        assertEquals("两个都读完了", last.getContent());
    }

    @Test
    void preToolUseHookRejectsAndFeedsErrorBack() throws Exception {
        Files.writeString(tempDir.resolve("hooked.txt"), "内容");
        var hooks = new HookEngine();
        hooks.addHook(new Hook("guard", HookEvent.PRE_TOOL_USE, null,
                new HookAction(HookActionType.PROMPT, null, "禁止执行该命令"), true, false, false));
        // 用 READ 工具：DEFAULT 模式权限直通，hook 拦截才会真正执行到
        var client = new FakeClient(
                Scripted.call("c1", "ReadFile", Map.of("file_path", "hooked.txt")),
                Scripted.text("知道了"));

        RunResult result = runLoop(client, false, hooks);

        assertTrue(result.historyText().contains("Hook 拦截"),
                "hook 拒绝应作为工具错误回流: " + result.historyText());
        assertFalse(result.historyText().contains("内容"), "被拦截的读不应执行")
        ;
        assertTrue(client.requests.size() == 2, "拒绝后循环继续");
    }

    @Test
    void turnStartHookInjectsReminderAndCleansUp() throws Exception {
        var hooks = new HookEngine();
        hooks.addHook(new Hook("h1", HookEvent.TURN_START, null,
                new HookAction(HookActionType.PROMPT, null, "每轮注入的提示"), false, false, false));
        var client = new FakeClient(Scripted.text("完成"));

        RunResult result = runLoop(client, false, hooks);

        // 请求时历史里带注入提醒
        String request = client.requests.get(0).stream()
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(request.contains("每轮注入的提示"), "TURN_START 注入应进请求");
        assertTrue(request.contains("<system-reminder>"), "注入应以 system-reminder 包裹");
        // 清理后不留在跨轮历史
        assertFalse(result.historyText().contains("每轮注入的提示"),
                "注入应请求后移除，不进持久历史");
    }
}
