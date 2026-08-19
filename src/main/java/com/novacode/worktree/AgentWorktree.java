package com.novacode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * 高层 worktree 操作（第 14 章 F3）：create（含 fast-resume）、remove、路径通知、只读 head。
 *
 * <p><b>fast-resume</b>：目标目录已存在时只读文件系统（`.git` 指针文件 + HEAD + ref），
 * 不调用 git —— 满足「目录已存在只读文件系统、不跑 git」的要求，并刷新 mtime 以便清理层判断存活。</p>
 */
public final class AgentWorktree {

    /** 一次 worktree 创建/恢复的结果。 */
    public record Result(Path worktreePath, String worktreeBranch, String headCommit, Path gitRoot) {}

    private AgentWorktree() {}

    /** 创建（或 fast-resume）一个 worktree。{@code name} 先经 {@link SlugValidator} 校验。 */
    public static Result create(Path gitRoot, String name, Path parentDir) {
        String err = SlugValidator.validate(name);
        if (err != null) throw new IllegalArgumentException("非法 worktree 名称: " + err);

        Path targetDir = parentDir.resolve(name).normalize();
        String branch = SlugValidator.branchName(name);

        if (Files.isDirectory(targetDir)) {
            // fast-resume：只读文件系统，不跑 git
            bumpMtime(targetDir);
            return new Result(targetDir, branch, readHead(targetDir), gitRoot);
        }

        WorktreeManager.create(gitRoot, branch, targetDir);
        return new Result(targetDir, branch, readHead(targetDir), gitRoot);
    }

    /** 删除 worktree 及其分支。 */
    public static void remove(Result r) {
        WorktreeManager.remove(r.gitRoot(), r.worktreePath(), r.worktreeBranch());
    }

    /** 生成注入子 Agent 的路径通知，告诉它被隔离在哪个目录。 */
    public static String buildNotice(Result r, Path originalCwd) {
        return "<worktree-notice>\n"
                + "You are working in an isolated Git worktree:\n"
                + "- Working directory: " + r.worktreePath() + "\n"
                + "- Branch: " + r.worktreeBranch() + "\n"
                + "- Base commit: " + (r.headCommit() == null ? "(unknown)" : r.headCommit()) + "\n"
                + "- Main agent working directory: " + originalCwd + "\n"
                + "Rules:\n"
                + "1. Use absolute paths under your working directory for all file operations.\n"
                + "2. Your changes are isolated to this worktree; do not touch files outside it.\n"
                + "3. Do not run git merge / rebase / push; leave merging to the main agent.\n"
                + "</worktree-notice>";
    }

    /** 只读解析 worktree 的当前 head commit：`.git` 指针 → HEAD → ref（含 packed-refs 兜底）。 */
    public static String readHead(Path worktreePath) {
        try {
            Path gitFile = worktreePath.resolve(".git");
            String content = Files.readString(gitFile).trim();
            Path gitDir;      // 该 worktree 自己的 gitdir（HEAD 在此）
            Path commonDir;   // 公共仓库 gitdir（refs 在此）
            if (content.startsWith("gitdir:")) {
                String p = content.substring("gitdir:".length()).trim();
                gitDir = Path.of(p);
                if (!gitDir.isAbsolute()) gitDir = worktreePath.resolve(gitDir).normalize();
                commonDir = resolveCommonDir(gitDir);
            } else if (Files.isDirectory(gitFile)) {
                gitDir = gitFile; // 普通仓库（非 worktree）
                commonDir = gitDir;
            } else {
                return null;
            }

            String head = Files.readString(gitDir.resolve("HEAD")).trim();
            if (head.startsWith("ref:")) {
                String ref = head.substring(4).trim(); // e.g. refs/heads/xxx
                Path refFile = commonDir.resolve(ref);
                if (Files.exists(refFile)) return Files.readString(refFile).trim();
                return readPackedRef(commonDir, ref);
            }
            return head; // detached：HEAD 本身就是 hash
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析公共仓库 gitdir（linked worktree 的 refs 存于公共仓库，而非自身 gitdir）。 */
    private static Path resolveCommonDir(Path gitDir) {
        try {
            Path commondirFile = gitDir.resolve("commondir");
            if (!Files.exists(commondirFile)) return gitDir;
            String c = Files.readString(commondirFile).trim();
            if (c.isEmpty()) return gitDir;
            Path p = Path.of(c);
            return p.isAbsolute() ? p.normalize() : gitDir.resolve(p).normalize();
        } catch (IOException e) {
            return gitDir;
        }
    }

    private static String readPackedRef(Path commonDir, String ref) {
        try {
            Path packed = commonDir.resolve("packed-refs");
            if (!Files.exists(packed)) return null;
            for (String line : Files.readAllLines(packed)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue;
                int sp = line.indexOf(' ');
                if (sp > 0 && line.substring(sp + 1).equals(ref)) {
                    return line.substring(0, sp);
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static void bumpMtime(Path dir) {
        try {
            Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {}
    }
}
