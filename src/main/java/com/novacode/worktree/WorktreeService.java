package com.novacode.worktree;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 子 Agent worktree 隔离门面（第 14 章 F6）。把「create → post-setup → notice」封装为
 * {@link #enter}，把「改动检测 → 删除/保留」封装为 {@link #exit}，供 {@code AgentTool} 在
 * 派发前后调用。worktree 统一落在 {@code <gitRoot>/.novacode/worktrees/}。
 */
public final class WorktreeService {

    /** 一次 enter 的结果：worktree 元信息 + 注入子 Agent 的路径通知。 */
    public record Enter(AgentWorktree.Result result, String notice) {
        public Path cwd() { return result.worktreePath(); }
        public String headCommit() { return result.headCommit(); }
    }

    private static final SecureRandom RNG = new SecureRandom();

    private final Path gitRoot;
    private final Path parentDir;
    private final Path sessionPath;

    public WorktreeService(Path gitRoot) {
        this.gitRoot = gitRoot;
        this.parentDir = gitRoot.resolve(".novacode").resolve("worktrees");
        this.sessionPath = WorktreeSessionStore.defaultPath(gitRoot);
    }

    public Path gitRoot() { return gitRoot; }

    /** 创建（或 fast-resume）worktree、初始化环境、生成通知，并记录会话。 */
    public Enter enter(String name) {
        long start = System.nanoTime();
        AgentWorktree.Result r = AgentWorktree.create(gitRoot, name, parentDir);
        PostCreationSetup.setup(r.worktreePath(), gitRoot);
        String notice = AgentWorktree.buildNotice(r, Path.of(System.getProperty("user.dir")));

        WorktreeSession session = new WorktreeSession(
                Path.of(System.getProperty("user.dir")),
                r.worktreePath(), name, r.worktreeBranch(),
                WorktreeManager.currentBranch(gitRoot), r.headCommit(),
                UUID.randomUUID().toString(),
                (System.nanoTime() - start) / 1_000_000L);
        WorktreeSessionStore.save(sessionPath, session);
        return new Enter(r, notice);
    }

    /** 退出：有改动则保留（false），无改动则删除并清会话（true）。 */
    public boolean exit(Enter e) {
        if (WorktreeChanges.hasChanges(e.cwd(), e.headCommit())) {
            return false; // 有未提交/未推送改动，拒绝删除
        }
        AgentWorktree.remove(e.result());
        WorktreeSessionStore.clear(sessionPath);
        return true;
    }

    /** 生成唯一临时目录名：{@code agent-<8位hex>}，匹配 {@link StaleCleanup#EPHEMERAL}。 */
    public String newEphemeralName() {
        byte[] b = new byte[4];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(8);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return "agent-" + sb;
    }
}
