package com.novacode.hook;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Hook 引擎（第 12 章）——生命周期钩子的注册、条件匹配与动作执行。
 * 动作失败只记录、绝不抛出（N1）；拦截类事件（pre_tool_use）不允许异步（F6）。
 */
public class HookEngine {

    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final List<Hook> hooks = new ArrayList<>();
    private final List<HookResult> notifications = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> fired = Collections.synchronizedSet(new HashSet<>());

    /** agent 类型动作执行器（占位，未注册则返回明确错误）。 */
    private BiFunction<String, HookContext, String> agentRunner;

    public void setAgentRunner(BiFunction<String, HookContext, String> runner) {
        this.agentRunner = runner;
    }

    public void addHook(Hook hook) {
        synchronized (hooks) { hooks.add(hook); }
    }

    /** 重载 hooks 并清空 once 去重集（程序运行期去重，不持久化）。 */
    public void loadHooks(List<Hook> hookList) {
        synchronized (hooks) {
            hooks.clear();
            hooks.addAll(hookList);
            fired.clear();
        }
    }

    // ── 集中校验 ──────────────────────────────────────────────────────────

    /** 聚合所有配置错误，不因首个错误中断。返回空列表表示合法。 */
    public static List<String> validate(List<Hook> hooks) {
        Set<HookEvent> validEvents = EnumSet.allOf(HookEvent.class);
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < hooks.size(); i++) {
            Hook h = hooks.get(i);
            String label = (h.id() != null && !h.id().isEmpty())
                    ? String.format("hook[%d] (id=%s)", i, h.id())
                    : String.format("hook[%d]", i);

            if (h.event() == null || !validEvents.contains(h.event())) {
                errors.add(String.format("%s: unknown event \"%s\"", label,
                        h.event() != null ? h.event().value() : "null"));
            }
            if (h.async() && h.event() == HookEvent.PRE_TOOL_USE) {
                errors.add(String.format("%s: 拦截事件 pre_tool_use 不允许 async", label));
            }
            if (HookCondition.mixesOperators(h.condition())) {
                errors.add(String.format("%s: 条件不允许混用 && 与 ||", label));
            }
            if (h.action() != null && h.action().timeout() != null
                    && h.action().timeout().isNegative()) {
                errors.add(String.format("%s: action.timeout must be >= 0 (got %s)",
                        label, h.action().timeout()));
            }
            if (h.action() == null || h.action().type() == null) {
                errors.add(String.format("%s: action.type is required", label));
                continue;
            }
            switch (h.action().type()) {
                case COMMAND -> {
                    if (isBlank(h.action().command())) {
                        errors.add(String.format("%s: action.command must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
                case PROMPT -> {
                    if (isBlank(h.action().message())) {
                        errors.add(String.format("%s: action.message must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
                case HTTP -> {
                    if (isBlank(h.action().url())) {
                        errors.add(String.format("%s: action.url must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    } else if (!validHttpUrl(h.action().url())) {
                        errors.add(String.format("%s: action.url must be a valid http(s) URL (got \"%s\")",
                                label, h.action().url()));
                    }
                }
                case AGENT -> {
                    if (isBlank(h.action().message()) && isBlank(h.action().command())) {
                        errors.add(String.format("%s: action.message (或 command 兜底) 必填 for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
            }
        }
        return errors;
    }

    private static boolean validHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ── 事件分发 ──────────────────────────────────────────────────────────

    /** 触发非拦截事件；async hook 后台执行并记通知，返回占位结果。 */
    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        for (Hook h : snapshotHooks()) {
            if (h.event() != ctx.event()) continue;
            if (!shouldFire(h, ctx)) continue;
            if (h.async()) {
                CompletableFuture.runAsync(() -> notifications.add(executeAction(h, ctx)));
                results.add(new HookResult(h.id(), "(async)", true, false));
                continue;
            }
            HookResult result = executeAction(h, ctx);
            results.add(result);
            notifications.add(result);
        }
        return results;
    }

    /** 触发注入类事件（session_start / turn_start），返回 prompt 动作的注入文本。 */
    public List<String> runInjectHooks(HookContext ctx) {
        List<String> injected = new ArrayList<>();
        for (Hook h : snapshotHooks()) {
            if (h.event() != ctx.event()) continue;
            if (!shouldFire(h, ctx)) continue;
            HookResult result = executeAction(h, ctx);
            notifications.add(result);
            if (h.action().type() == HookActionType.PROMPT && result.success()) {
                injected.add(result.output());
            }
        }
        return injected;
    }

    /** 触发 pre_tool_use 拦截；命中 reject 返回拒绝结果。 */
    public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args) {
        HookContext ctx = new HookContext(HookEvent.PRE_TOOL_USE, toolName, args,
                HookContext.filePathOf(args), null, null);
        for (Hook h : snapshotHooks()) {
            if (h.event() != HookEvent.PRE_TOOL_USE) continue;
            if (!shouldFire(h, ctx)) continue;
            HookResult result = executeAction(h, ctx);
            notifications.add(result);
            if (h.reject()) {
                String msg = result.output();
                if (msg == null || msg.isEmpty()) msg = "blocked by hook " + h.id();
                return new PreToolResult(true, msg);
            }
        }
        return new PreToolResult(false, "");
    }

    /** 取出并清空累积通知（线程安全）。 */
    public List<HookResult> drainNotifications() {
        synchronized (notifications) {
            List<HookResult> result = List.copyOf(notifications);
            notifications.clear();
            return result;
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private boolean shouldFire(Hook h, HookContext ctx) {
        if (h.condition() != null && !h.condition().isEmpty()
                && !HookCondition.evaluate(h.condition(), ctx)) {
            return false;
        }
        if (h.once()) {
            if (h.id() != null && !h.id().isEmpty()) {
                if (fired.contains(h.id())) return false;
                fired.add(h.id());
            }
        }
        return true;
    }

    private List<Hook> snapshotHooks() {
        synchronized (hooks) { return new ArrayList<>(hooks); }
    }

    private HookResult executeAction(Hook h, HookContext ctx) {
        return switch (h.action().type()) {
            case COMMAND -> executeCommand(h, ctx);
            case PROMPT -> new HookResult(h.id(), h.action().message(), true, h.reject());
            case HTTP -> executeHTTP(h, ctx);
            case AGENT -> executeAgent(h, ctx);
        };
    }

    private HookResult executeCommand(Hook h, HookContext ctx) {
        Duration timeout = h.action().timeout() != null && !h.action().timeout().isZero()
                ? h.action().timeout() : DEFAULT_COMMAND_TIMEOUT;
        String command = ctx.expand(h.action().command());
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            Map<String, String> env = pb.environment();
            env.put("NOVACODE_EVENT", ctx.event() != null ? ctx.event().value() : "");
            env.put("NOVACODE_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
            env.put("NOVACODE_FILE_PATH", ctx.filePath() != null ? ctx.filePath() : "");

            Process proc = pb.start();
            // 并发排空 stdout/stderr，避免高输出命令因管道缓冲写满而卡死（对照 BashTool）。
            var stdoutBuf = new java.io.ByteArrayOutputStream();
            var stderrBuf = new java.io.ByteArrayOutputStream();
            Thread outT = Thread.startVirtualThread(() -> drain(proc.getInputStream(), stdoutBuf));
            Thread errT = Thread.startVirtualThread(() -> drain(proc.getErrorStream(), stderrBuf));

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new HookResult(h.id(), "command timed out after " + timeout, false, h.reject());
            }
            outT.join();
            errT.join();
            String stdout = stdoutBuf.toString().strip();
            String stderr = stderrBuf.toString().strip();
            int code = proc.exitValue();
            String output = stdout;
            if (!stderr.isEmpty()) output = output.isEmpty() ? stderr : output + "\n" + stderr;
            return new HookResult(h.id(), output, code == 0, h.reject());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new HookResult(h.id(), "Failed to execute hook: " + e.getMessage(), false, h.reject());
        }
    }

    /** 把输入流读入缓冲区（供并发排空线程用，进程退出/流关闭即结束）。 */
    private static void drain(InputStream in, java.io.ByteArrayOutputStream out) {
        try (in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException ignored) {
            // 进程已退出 / 流已关闭
        }
    }

    private HookResult executeHTTP(Hook h, HookContext ctx) {
        String method = h.action().method() != null && !h.action().method().isEmpty()
                ? h.action().method().toUpperCase() : "POST";
        Duration timeout = h.action().timeout() != null && !h.action().timeout().isZero()
                ? h.action().timeout() : DEFAULT_HTTP_TIMEOUT;
        String url = ctx.expand(h.action().url());
        String body = h.action().body();
        if (body != null && !body.isEmpty()) {
            body = ctx.expand(body);
        } else {
            body = String.format(
                    "{\"event\":\"%s\",\"tool\":\"%s\",\"file_path\":\"%s\",\"message\":\"%s\",\"error\":\"%s\"}",
                    ctx.event() != null ? ctx.event().value() : "",
                    ctx.toolName() != null ? ctx.toolName() : "",
                    ctx.filePath() != null ? ctx.filePath() : "",
                    ctx.message() != null ? escapeJson(ctx.message()) : "",
                    ctx.error() != null ? escapeJson(ctx.error()) : "");
        }
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            boolean hasContentType = false;
            if (h.action().headers() != null) {
                for (var entry : h.action().headers().entrySet()) {
                    reqBuilder.header(entry.getKey(), ctx.expand(entry.getValue()));
                    if ("content-type".equalsIgnoreCase(entry.getKey())) hasContentType = true;
                }
            }
            if (!hasContentType && !body.isEmpty()) {
                reqBuilder.header("Content-Type", "application/json");
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            String respBody = resp.body();
            if (respBody != null && respBody.length() > 65536) respBody = respBody.substring(0, 65536);
            return new HookResult(h.id(),
                    String.format("HTTP %d: %s", resp.statusCode(), respBody != null ? respBody.strip() : ""),
                    ok, h.reject());
        } catch (Exception e) {
            return new HookResult(h.id(), e.getMessage(), false, h.reject());
        }
    }

    private HookResult executeAgent(Hook h, HookContext ctx) {
        if (agentRunner == null) {
            return new HookResult(h.id(),
                    "agent-type hook configured but no AgentRunner registered", false, h.reject());
        }
        String prompt = h.action().message();
        if (prompt == null || prompt.isEmpty()) prompt = h.action().command();
        prompt = ctx.expand(prompt);
        try {
            return new HookResult(h.id(), agentRunner.apply(prompt, ctx), true, h.reject());
        } catch (Exception e) {
            return new HookResult(h.id(), e.getMessage(), false, h.reject());
        }
    }

    private static boolean isBlank(String s) { return s == null || s.strip().isEmpty(); }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
