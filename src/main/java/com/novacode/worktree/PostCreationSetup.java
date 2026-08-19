package com.novacode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Worktree 创建后的环境初始化（第 14 章 F4）。四步各自独立容错，任一失败只记录、
 * 不抛异常、不阻断其余步骤：
 * <ol>
 *   <li>复制本地配置 {@code .novacode/settings.local.json}。</li>
 *   <li>配置 git hooks：主仓库存在 {@code .husky} 时设置 {@code core.hooksPath=.husky}。</li>
 *   <li>按 {@code .novacode/worktree-symlinks.txt} 软链大依赖目录（Windows 降级 junction）。</li>
 *   <li>按 {@code .worktreeinclude} 复制被 gitignore 但需要的文件。</li>
 * </ol>
 */
public final class PostCreationSetup {

    private static final String SETTINGS_LOCAL = ".novacode/settings.local.json";
    private static final String SYMLINKS = ".novacode/worktree-symlinks.txt";
    private static final String INCLUDE = ".worktreeinclude";

    private PostCreationSetup() {}

    public static void setup(Path worktreePath, Path gitRoot) {
        copySettingsLocal(worktreePath, gitRoot);
        configureHooksPath(worktreePath, gitRoot);
        symlinkDirectories(worktreePath, gitRoot);
        copyIncludeFiles(worktreePath, gitRoot);
    }

    // ① 本地配置
    private static void copySettingsLocal(Path worktreePath, Path gitRoot) {
        copyIfExists(gitRoot.resolve(SETTINGS_LOCAL), worktreePath.resolve(SETTINGS_LOCAL));
    }

    // ② git hooks
    private static void configureHooksPath(Path worktreePath, Path gitRoot) {
        if (!Files.isDirectory(gitRoot.resolve(".husky"))) return;
        WorktreeManager.runGit(worktreePath, "config", "core.hooksPath", ".husky");
    }

    // ③ 软链大依赖目录
    private static void symlinkDirectories(Path worktreePath, Path gitRoot) {
        List<String> entries = readLines(gitRoot.resolve(SYMLINKS));
        for (String entry : entries) {
            if (entry.isBlank() || entry.startsWith("#")) continue;
            Path src = gitRoot.resolve(entry).normalize();
            Path dst = worktreePath.resolve(entry).normalize();
            if (!Files.isDirectory(src)) continue;
            try {
                Files.createDirectories(dst.getParent());
                Files.createSymbolicLink(dst, src);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                // Windows 无管理员时符号链接失败，降级 junction
                junction(dst, src);
            }
        }
    }

    // ④ .worktreeinclude：复制被忽略但需要的文件
    private static void copyIncludeFiles(Path worktreePath, Path gitRoot) {
        List<String> entries = readLines(gitRoot.resolve(INCLUDE));
        for (String entry : entries) {
            if (entry.isBlank() || entry.startsWith("#")) continue;
            Path src = gitRoot.resolve(entry).normalize();
            if (!Files.isRegularFile(src)) continue;
            copyIfExists(src, worktreePath.resolve(entry).normalize());
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static void copyIfExists(Path src, Path dst) {
        try {
            if (!Files.exists(src)) return;
            Path parent = dst.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Windows 目录 junction（无需管理员），失败则跳过。 */
    private static void junction(Path link, Path target) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                    "mklink", "/J", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }
}
