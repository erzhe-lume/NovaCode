package com.novacode.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Git worktree CLI 封装（第 14 章 F1）。所有操作通过 {@code git} 子进程完成，
 * 不依赖任何 Git 库。命令失败统一抛出或返回 fail-closed 结果，由上层决定降级策略。
 */
public final class WorktreeManager {

    private static final long GIT_TIMEOUT_SECONDS = 30;

    /** 一次 git 调用的结果：退出码 + 合并后的 stdout/stderr。 */
    public record GitResult(int exit, String out) {
        public boolean ok() { return exit == 0; }
    }

    /** 单个 worktree 的解析结果。 */
    public record WorktreeInfo(String branch, String path, String headCommit) {}

    private WorktreeManager() {}

    /**
     * 运行 git 命令。{@code dir} 非空且为目录时作为进程工作目录（等价 {@code git -C dir}）。
     * 输出在虚拟线程上排空，避免管道缓冲区死锁；超时则强制终止。
     */
    public static GitResult runGit(Path dir, String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git"));
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (dir != null && Files.isDirectory(dir)) pb.directory(dir.toFile());

            Process p = pb.start();
            String[] out = { "" };
            Thread drainer = Thread.startVirtualThread(() -> {
                try (var is = p.getInputStream()) {
                    out[0] = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
            });
            boolean finished = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            drainer.join(1000);
            int exit = finished ? p.exitValue() : -1;
            return new GitResult(exit, out[0]);
        } catch (Exception e) {
            return new GitResult(-1, "");
        }
    }

    /** 探测仓库根；非 git 仓库返回 null。 */
    public static String detectGitRoot(Path start) {
        GitResult r = runGit(start, "rev-parse", "--show-toplevel");
        if (!r.ok()) return null;
        String out = r.out().trim();
        return out.isEmpty() ? null : out;
    }

    /** 新建 worktree（`git worktree add -B <branch> <target>`），失败抛异常。 */
    public static void create(Path gitRoot, String branch, Path targetDir) {
        try {
            Path parent = targetDir.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException ignored) {}
        GitResult r = runGit(gitRoot, "worktree", "add", "-B", branch, targetDir.toString());
        if (!r.ok()) {
            throw new IllegalStateException("git worktree add 失败: " + r.out().trim());
        }
    }

    /** 删除 worktree（`--force`），并尽力删除其分支。 */
    public static void remove(Path gitRoot, Path targetDir, String branch) {
        runGit(gitRoot, "worktree", "remove", "--force", targetDir.toString());
        if (branch != null && !branch.isBlank() && !"HEAD".equals(branch)) {
            runGit(gitRoot, "branch", "-D", branch); // 分支可能已被移除，忽略失败
        }
    }

    /** 列出所有 worktree（解析 {@code git worktree list --porcelain}）。 */
    public static List<WorktreeInfo> list(Path gitRoot) {
        List<WorktreeInfo> result = new ArrayList<>();
        GitResult r = runGit(gitRoot, "worktree", "list", "--porcelain");
        if (!r.ok()) return result;

        String path = null, branch = null, head = null;
        boolean first = true;
        for (String line : r.out().split("\\R", -1)) {
            if (line.startsWith("worktree ")) {
                if (!first && path != null) {
                    result.add(new WorktreeInfo(branch, path, head));
                }
                first = false;
                path = line.substring("worktree ".length());
                branch = null;
                head = null;
            } else if (line.startsWith("HEAD ")) {
                head = line.substring("HEAD ".length());
            } else if (line.startsWith("branch ")) {
                String b = line.substring("branch ".length());
                branch = b.startsWith("refs/heads/") ? b.substring("refs/heads/".length()) : b;
            }
        }
        if (!first && path != null) result.add(new WorktreeInfo(branch, path, head));
        return result;
    }

    /** 当前分支名；detached 时返回 "HEAD"。 */
    public static String currentBranch(Path dir) {
        GitResult r = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD");
        if (!r.ok()) return null;
        String out = r.out().trim();
        return out.isEmpty() ? null : out;
    }

    /** 是否存在上游分支。 */
    public static boolean hasUpstream(Path dir) {
        GitResult r = runGit(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
        return r.ok();
    }
}
