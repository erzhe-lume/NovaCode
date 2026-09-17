package com.novacode.subagent;

import com.novacode.agent.Agent;
import com.novacode.agent.AgentEvent;
import com.novacode.config.ProviderConfig;
import com.novacode.model.ChatMessage;
import com.novacode.permission.Blacklist;
import com.novacode.permission.PathSandbox;
import com.novacode.permission.PermissionEngine;
import com.novacode.permission.PermissionMode;
import com.novacode.protocol.LlmClient;
import com.novacode.protocol.StreamEvent;
import com.novacode.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** SubAgentRunner：排空语义（完成/错误/超时/续跑）、模型解析、子 Agent 隔离。 */
class SubAgentRunnerTest {

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

    private SubAgentRunner newRunner() {
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        var engine = new PermissionEngine(new Blacklist(), new PathSandbox(tempDir),
                new com.novacode.permission.RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
        return new SubAgentRunner(new LlmClient() {
            @Override
            public java.util.concurrent.BlockingQueue<StreamEvent> stream(List<ChatMessage> h, String s) {
                throw new UnsupportedOperationException("parent client not used in these tests");
            }
        }, "openai-compat", cfg, engine);
    }

    // ── drain 排空语义 ───────────────────────────────────────────────

    @Test
    void drainAccumulatesOutputToolCountAndTokens() {
        var q = new LinkedBlockingQueue<AgentEvent>();
        q.add(new AgentEvent.StreamText("分析完成："));
        q.add(new AgentEvent.ToolResultEvent("t1", "Grep", "3 hits", false, 0.1));
        q.add(new AgentEvent.ToolResultEvent("t2", "ReadFile", "...", false, 0.2));
        q.add(new AgentEvent.UsageEvent(120, 30, 0, 0));
        q.add(new AgentEvent.LoopComplete(1));

        SubAgentRunner.RunResult result = newRunner().drain(q, "搜索用例", "explorer", 0, false);

        assertInstanceOf(SubAgentRunner.RunResult.Done.class, result);
        var outcome = result.outcome();
        assertTrue(outcome.ok());
        assertTrue(outcome.output().contains("分析完成"), outcome.output());
        assertEquals(2, outcome.toolCount());
        assertEquals(120, outcome.inputTokens());
        assertEquals(30, outcome.outputTokens());
        assertNull(outcome.error());
    }

    @Test
    void drainErrorEventShortCircuitsAsFailure() {
        var q = new LinkedBlockingQueue<AgentEvent>();
        q.add(new AgentEvent.StreamText("部分输出"));
        q.add(new AgentEvent.ErrorEvent("stream exploded"));

        var outcome = newRunner().drain(q, "任务", "spec", 0, false).outcome();

        assertFalse(outcome.ok());
        assertEquals("stream exploded", outcome.error());
        assertTrue(outcome.output().contains("部分输出"), "失败也应保留已有部分输出");
    }

    @Test
    void drainWithoutDeadlineFailsWhenQueueGoesQuiet() {
        // 空队列 + 无 deadline → poll 120s 太慢；deadline 路径用小超时覆盖（下一个测试）。
        // 这里验证 detach=false + 小超时 → Done(failed)。
        var q = new LinkedBlockingQueue<AgentEvent>();
        var outcome = newRunner().drain(q, "任务", "spec", 1, false).outcome();
        assertFalse(outcome.ok());
        assertTrue(outcome.error().contains("timeout"), outcome.error());
    }

    @Test
    void detachOnTimeoutReturnsContinuationThatFinishes() throws Exception {
        var q = new LinkedBlockingQueue<AgentEvent>();
        q.add(new AgentEvent.StreamText("超时前的部分")); // 立刻被消费，然后队列静默到超时

        SubAgentRunner.RunResult result = newRunner().drain(q, "长任务", "worker", 1, true);
        assertInstanceOf(SubAgentRunner.RunResult.Detached.class, result);
        assertTrue(result.outcome().output().contains("超时前的部分"));

        // 后台继续产出 → 续跑拿到完整结果
        var detach = (SubAgentRunner.RunResult.Detached) result;
        new Thread(() -> {
            try {
                Thread.sleep(150);
                q.put(new AgentEvent.StreamText(" + 后续完成"));
                q.put(new AgentEvent.LoopComplete(2));
            } catch (InterruptedException ignored) {
            }
        }).start();

        SubAgentRunner.Outcome finished = detach.continuation().finish();
        assertTrue(finished.ok(), finished.error());
        assertTrue(finished.output().contains("后续完成"), finished.output());
    }

    @Test
    void progressListenerReceivesToolAndDoneEvents() {
        var q = new LinkedBlockingQueue<AgentEvent>();
        q.add(new AgentEvent.ToolResultEvent("t1", "Grep", "hits", false, 0.1));
        q.add(new AgentEvent.LoopComplete(1));

        var events = new AtomicInteger();
        var runner = newRunner();
        runner.setProgressListener(p -> events.incrementAndGet());
        runner.drain(q, "任务", "spec", 0, false);

        assertEquals(2, events.get(), "工具完成 + 整体完成各一次");
    }

    // ── selectClient 模型解析 ────────────────────────────────────────

    @Test
    void clientResolutionPrecedence() {
        var runner = newRunner();
        LlmClient parent = newRunner() == null ? null : null; // placeholder
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        var engine = new PermissionEngine(new Blacklist(), new PathSandbox(tempDir),
                new com.novacode.permission.RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
        LlmClient parentClient = new com.novacode.protocol.OpenAiCompatClient(cfg, "sys");
        var r = new SubAgentRunner(parentClient, "openai-compat", cfg, engine);

        // inherit / null / 空 → 父 client
        assertSame(parentClient, r.selectClient(null, null));
        assertSame(parentClient, r.selectClient("inherit", null));
        assertSame(parentClient, r.selectClient("", ""));

        // spec.model + resolver → 解析别名
        LlmClient aliased = new com.novacode.protocol.OpenAiCompatClient(cfg, "alias");
        r.setModelResolver(name -> "deepseek-pro".equals(name) ? aliased : null);
        assertSame(aliased, r.selectClient("deepseek-pro", null));

        // override 优先于 spec.model
        assertSame(aliased, r.selectClient("inherit", "deepseek-pro"));

        // resolver 未命中 → 回落父
        assertSame(parentClient, r.selectClient("unknown-alias", null));
    }

    // ── buildAgent 隔离语义 ──────────────────────────────────────────

    @Test
    void buildAgentWiresMaxTurnsIntoPolicy() {
        var runner = newRunner();
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        var client = new com.novacode.protocol.OpenAiCompatClient(cfg, "sys");
        Agent agent = runner.buildAgent(ToolRegistry.createDefault(), client, PermissionMode.DEFAULT, 7);
        assertEquals(7, agent.policy().getMaxIterations());
        // maxTurns<=0 不覆盖（保持默认 50）
        Agent defaultAgent = runner.buildAgent(ToolRegistry.createDefault(), client, PermissionMode.DEFAULT, 0);
        assertEquals(com.novacode.agent.LoopPolicy.DEFAULT_MAX_ITERATIONS,
                defaultAgent.policy().getMaxIterations());
    }

    @Test
    void subAgentWriteDeniedWithoutHitlInDefaultMode() throws Exception {
        // forSubAgent：prompter=null → DEFAULT 模式下写落 Ask 时直接 Deny（无人在回路，N7）
        var runner = newRunner();
        var client = scriptedClient(
                call("c1", "WriteFile", Map.of("file_path", "out.txt", "content", "x")),
                text("收到"));
        Agent agent = runner.buildAgent(ToolRegistry.createDefault(), client, PermissionMode.DEFAULT, 0);

        var history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "写文件"));
        var result = runner.run(agent, history, "sys", false, "写文件任务", "writer", 30, false);

        assertInstanceOf(SubAgentRunner.RunResult.Done.class, result);
        assertTrue(result.outcome().ok(), "循环正常结束: " + result.outcome().error());
        String allOutput = history.stream()
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allOutput.contains("权限拒绝"),
                "子 Agent 写文件应被拒绝（无确认通道）: " + allOutput);
        assertFalse(Files.exists(tempDir.resolve("out.txt")), "被拒的写不应执行");
    }

    @Test
    void subAgentHonorsAcceptEditsModeWithoutHitl() throws Exception {
        // 对照组：ACCEPT_EDITS 模式正确传递给子 Agent —— 写不落 Ask 直接执行
        var runner = newRunner();
        var client = scriptedClient(
                call("c1", "WriteFile", Map.of("file_path", "allowed.txt", "content", "内容")),
                text("写好了"));
        Agent agent = runner.buildAgent(ToolRegistry.createDefault(), client, PermissionMode.ACCEPT_EDITS, 0);

        var history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage(ChatMessage.Role.USER, "写文件"));
        var result = runner.run(agent, history, "sys", false, "写文件任务", "writer", 30, false);

        assertTrue(result.outcome().ok(), result.outcome().error());
        String allOutput = history.stream()
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allOutput.contains("Successfully wrote"), "ACCEPT_EDITS 下写应执行: " + allOutput);
        assertTrue(Files.exists(tempDir.resolve("allowed.txt")), "文件应被创建");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static LlmClient scriptedClient(ScriptStep... steps) {
        var queue = new java.util.ArrayDeque<List<StreamEvent>>();
        for (ScriptStep s : steps) queue.add(s.events);
        return new LlmClient() {
            @Override
            public java.util.concurrent.BlockingQueue<StreamEvent> stream(List<ChatMessage> h, String s) {
                var q = new LinkedBlockingQueue<StreamEvent>();
                List<StreamEvent> next = queue.poll();
                if (next != null) q.addAll(next);
                q.add(new StreamEvent.StreamEnd("end_turn"));
                return q;
            }
        };
    }

    private record ScriptStep(List<StreamEvent> events) {}

    private static ScriptStep call(String id, String name, Map<String, Object> args) {
        return new ScriptStep(List.of(new StreamEvent.ToolCallComplete(id, name, args)));
    }

    private static ScriptStep text(String t) {
        return new ScriptStep(List.of(new StreamEvent.TextDelta(t)));
    }
}
