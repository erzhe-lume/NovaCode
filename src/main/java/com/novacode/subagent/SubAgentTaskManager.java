package com.novacode.subagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 后台子 Agent 任务管理器（第 13 章 F8）。内存态，追踪状态 / 结果 / 用量，
 * 累积通知供主对话在轮间排空。三种进入后台的方式：
 * <ul>
 *   <li>显式指定：AgentTool 的 {@code run_in_background=true}。</li>
 *   <li>Fork 强制：Fork 式子 Agent 一律走 {@link #spawnBackground}。</li>
 *   <li>超时自动：前台超时经 {@link #continueInBackground} 续跑；后台看门狗
 *       {@link #spawnBackground} 的 timeoutSeconds 到点自动标 FAILED。</li>
 * </ul>
 */
public class SubAgentTaskManager {

    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Task(String id, String name, TaskStatus status, String output, String error,
                       int inputTokens, int outputTokens) {}

    public record TaskNotification(String taskId, String name, TaskStatus status, String output) {}

    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();
    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    private static class TaskEntry {
        final String id;
        final String name;
        volatile TaskStatus status = TaskStatus.PENDING;
        volatile String output = "";
        volatile String error = "";
        volatile int inTok = 0, outTok = 0;
        volatile Thread thread;

        TaskEntry(String id, String name) { this.id = id; this.name = name; }
    }

    public synchronized String createTask(String name) {
        String id = "task_" + nextId.incrementAndGet();
        tasks.put(id, new TaskEntry(id, name));
        return id;
    }

    public synchronized void setRunning(String id, Thread thread) {
        TaskEntry e = tasks.get(id);
        if (e != null) { e.status = TaskStatus.RUNNING; e.thread = thread; }
    }

    public synchronized void setCompleted(String id, String output, int inTok, int outTok) {
        TaskEntry e = tasks.get(id);
        if (e == null || terminal(e)) return;
        e.status = TaskStatus.COMPLETED;
        e.output = output;
        e.inTok = inTok;
        e.outTok = outTok;
        notifications.add(new TaskNotification(id, e.name, TaskStatus.COMPLETED, output));
    }

    public synchronized void setFailed(String id, String err) {
        TaskEntry e = tasks.get(id);
        if (e == null || terminal(e)) return;
        e.status = TaskStatus.FAILED;
        e.error = err;
        notifications.add(new TaskNotification(id, e.name, TaskStatus.FAILED, err));
    }

    /** 取消运行中任务：置 CANCELLED 并打断运行线程（终态，不再被 setCompleted/setFailed 覆盖）。 */
    public synchronized void cancelTask(String id) {
        TaskEntry e = tasks.get(id);
        if (e != null && e.status == TaskStatus.RUNNING) {
            e.status = TaskStatus.CANCELLED;
            if (e.thread != null) e.thread.interrupt();
            notifications.add(new TaskNotification(id, e.name, TaskStatus.CANCELLED, ""));
        }
    }

    public synchronized List<TaskNotification> drainNotifications() {
        var result = new ArrayList<>(notifications);
        notifications.clear();
        return result;
    }

    public synchronized Task getTask(String id) {
        TaskEntry e = tasks.get(id);
        return e == null ? null : toTask(e);
    }

    public synchronized List<Task> listTasks() {
        return tasks.values().stream().map(this::toTask).toList();
    }

    /** 后台跑 work（返回 Outcome），看门狗 timeoutSeconds 到点打断并标 FAILED。返回 task id。 */
    public String spawnBackground(String taskName, Supplier<SubAgentRunner.Outcome> work, long timeoutSeconds) {
        String id = createTask(taskName);
        Thread t = Thread.startVirtualThread(() -> {
            try {
                SubAgentRunner.Outcome o = work.get();
                if (o.ok()) setCompleted(id, o.output(), o.inputTokens(), o.outputTokens());
                else setFailed(id, o.error() == null ? "unknown error" : o.error());
            } catch (Exception ex) {
                setFailed(id, ex.getMessage() == null ? "unknown error" : ex.getMessage());
            }
        });
        setRunning(id, t);
        if (timeoutSeconds > 0) {
            Thread.startVirtualThread(() -> watchdog(id, t, timeoutSeconds));
        }
        return id;
    }

    /** 前台超时续跑：续排空同一队列，跑完置 COMPLETED/FAILED。返回新 task id。 */
    public String continueInBackground(String taskName, SubAgentRunner.Continuation continuation) {
        String id = createTask(taskName);
        Thread t = Thread.startVirtualThread(() -> {
            try {
                SubAgentRunner.Outcome o = continuation.finish();
                if (o.ok()) setCompleted(id, o.output(), o.inputTokens(), o.outputTokens());
                else setFailed(id, o.error() == null ? "unknown error" : o.error());
            } catch (Exception ex) {
                setFailed(id, ex.getMessage() == null ? "unknown error" : ex.getMessage());
            }
        });
        setRunning(id, t);
        return id;
    }

    private void watchdog(String id, Thread target, long timeoutSeconds) {
        try {
            Thread.sleep(timeoutSeconds * 1000L);
        } catch (InterruptedException e) {
            return;
        }
        // 先置 FAILED 再打断：让超时消息覆盖 worker 后续的 "Interrupted" setFailed。
        synchronized (this) {
            TaskEntry e = tasks.get(id);
            if (e == null || e.status != TaskStatus.RUNNING) return;
            e.status = TaskStatus.FAILED;
            e.error = "timeout after " + timeoutSeconds + "s";
            notifications.add(new TaskNotification(id, e.name, TaskStatus.FAILED, e.error));
        }
        target.interrupt();
    }

    private static boolean terminal(TaskEntry e) {
        return e.status == TaskStatus.COMPLETED || e.status == TaskStatus.FAILED
                || e.status == TaskStatus.CANCELLED;
    }

    private Task toTask(TaskEntry e) {
        return new Task(e.id, e.name, e.status, e.output, e.error, e.inTok, e.outTok);
    }
}
