package com.novacode.worktree;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 过期临时 worktree 后台清理（第 14 章 F7）。清理前做三层安全过滤，任一不过即跳过：
 * <ol>
 *   <li>目录名匹配临时名模式（{@link #EPHEMERAL}）；</li>
 *   <li>目录 mtime 超过 {@code maxAgeMs}（存活阈值）；</li>
 *   <li>fail-closed 改动检查（有未提交改动则保留）。</li>
 * </ol>
 */
public final class StaleCleanup {

    /** 临时 worktree 目录名模式：{@code agent-<8位小写hex>}。 */
    public static final Pattern EPHEMERAL = Pattern.compile("^agent-[0-9a-f]{8}$");

    private StaleCleanup() {}

    /** 目录名是否匹配临时名模式（只看名字，不看内容）。 */
    public static boolean isEphemeral(Path dir) {
        Path name = dir.getFileName();
        return name != null && EPHEMERAL.matcher(name.toString()).matches();
    }

    /**
     * 清理 {@code parentDir} 下过期且无改动的临时 worktree，返回被删除的目录列表。
     * 任一层不过即跳过；删除失败不影响其余目录。
     */
    public static List<Path> cleanup(Path parentDir, long maxAgeMs) {
        List<Path> removed = new ArrayList<>();
        if (parentDir == null || !Files.isDirectory(parentDir)) return removed;

        // parentDir 位于主仓库内 → 探测主仓库根（而非每个 worktree 自身的 top-level）
        String gitRoot = WorktreeManager.detectGitRoot(parentDir);
        if (gitRoot == null) return removed;

        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                if (!isEphemeral(dir)) continue;                       // ① 名字
                try {
                    long mtime = Files.getLastModifiedTime(dir).toMillis();
                    if (now - mtime < maxAgeMs) continue;              // ② 存活期
                    if (WorktreeChanges.hasLocalChanges(dir)) continue; // ③ fail-closed 改动
                    String branch = WorktreeManager.currentBranch(dir);
                    WorktreeManager.remove(Path.of(gitRoot), dir, branch);
                    removed.add(dir);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return removed;
    }

    /** 启动周期清理守护（可选）；返回可关闭的句柄，仅供测试/优雅停机使用。 */
    public static ScheduledExecutorService startCleanupLoop(Path parentDir, long maxAgeMs, long intervalMs) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worktree-cleanup");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(() -> cleanup(parentDir, maxAgeMs), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return executor;
    }
}
