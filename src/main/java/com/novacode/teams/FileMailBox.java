package com.novacode.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每成员邮箱文件 + 锁文件并发协议（第 15 章 F4/N2/N3）。
 *
 * <p>邮箱落盘为 {@code <teamDir>/inboxes/<recipient>.json}，消息列表 JSON 数组。并发安全
 * 分两层：进程内 {@link ReentrantLock} 串行化对同一收件箱的操作；跨进程用
 * {@code <name>.json.lock} 文件锁（{@link Files#createFile} 原子抢锁）。拿不到锁指数退避
 * 重试（5→80ms 带抖动），总时长超 {@link #LOCK_ACQUIRE_TIMEOUT_MS} 抛异常（不静默丢消息）；
 * 锁文件超过 {@link #STALE_LOCK_AGE_SECONDS} 视为持有者已崩溃、可删除接管。</p>
 */
public final class FileMailBox {

    private static final long LOCK_ACQUIRE_TIMEOUT_MS = 5000;
    private static final long STALE_LOCK_AGE_SECONDS = 10;
    private static final long BACKOFF_MIN_MS = 5;
    private static final long BACKOFF_MAX_MS = 80;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Random JITTER = new Random();

    private final Path inboxesDir;
    private final ConcurrentMap<String, ReentrantLock> inProcessLocks = new ConcurrentHashMap<>();

    public FileMailBox(Path inboxesDir) {
        this.inboxesDir = inboxesDir;
    }

    /** 给收件人投递一条消息（进程内锁 + 文件锁双重保护）。 */
    public void send(String recipient, MailMessage msg) {
        withLockVoid(recipient, () -> {
            Path f = mailboxFile(recipient);
            Path lockFile = lockFile(f);
            withFileLock(lockFile, () -> {
                List<MailMessage> msgs = readAllUnlocked(f);
                msgs.add(msg);
                writeAll(f, msgs);
            });
        });
    }

    /** 未读消息列表（不标记已读）。 */
    public List<MailMessage> readUnread(String recipient) {
        return withLock(recipient, () ->
                readAllUnlocked(mailboxFile(recipient)).stream().filter(m -> !m.read()).toList());
    }

    /** 全部消息（进程内锁，只读不加文件锁）。 */
    public List<MailMessage> list(String recipient) {
        return withLock(recipient, () -> readAllUnlocked(mailboxFile(recipient)));
    }

    /** 全部标记已读（进程内锁 + 文件锁）。 */
    public void markAllRead(String recipient) {
        withLockVoid(recipient, () -> {
            Path f = mailboxFile(recipient);
            Path lockFile = lockFile(f);
            withFileLock(lockFile, () -> {
                List<MailMessage> msgs = readAllUnlocked(f).stream()
                        .map(m -> m.read() ? m : m.withRead(true)).toList();
                writeAll(f, msgs);
            });
        });
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private Path mailboxFile(String recipient) {
        return inboxesDir.resolve(recipient + ".json");
    }

    private static Path lockFile(Path mailboxFile) {
        return Path.of(mailboxFile.toString() + ".lock");
    }

    private List<MailMessage> readAllUnlocked(Path f) {
        if (!Files.exists(f)) return new ArrayList<>();
        try {
            List<Map<String, Object>> raw = JSON.readValue(f.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (raw == null) return new ArrayList<>();
            List<MailMessage> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                MailMessage mm = MailMessage.fromMap(m);
                if (mm != null) out.add(mm);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>(); // 损坏 → 视为空（容错）
        }
    }

    private static void writeAll(Path f, List<MailMessage> msgs) {
        try {
            Path parent = f.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<Map<String, Object>> maps = new ArrayList<>();
            for (MailMessage m : msgs) maps.add(m.toMap());
            JSON.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), maps);
        } catch (IOException e) {
            throw new IllegalStateException("mailbox write failed: " + e.getMessage());
        }
    }

    private static void acquireFileLock(Path lockFile) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LOCK_ACQUIRE_TIMEOUT_MS;
        long backoff = BACKOFF_MIN_MS;
        while (true) {
            try {
                Path parent = lockFile.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.createFile(lockFile);
                return;
            } catch (FileAlreadyExistsException e) {
                if (isStale(lockFile)) {
                    try {
                        Files.deleteIfExists(lockFile);
                    } catch (IOException ignored) {}
                    continue; // 接管陈旧锁后立即重试
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new IllegalStateException("mailbox lock timeout: " + lockFile.getFileName());
                }
                Thread.sleep(backoff + JITTER.nextInt(10));
                backoff = Math.min(BACKOFF_MAX_MS, backoff * 2);
            } catch (IOException e) {
                throw new IllegalStateException("mailbox lock error: " + e.getMessage());
            }
        }
    }

    private static boolean isStale(Path lockFile) {
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(lockFile).toMillis();
            return age > STALE_LOCK_AGE_SECONDS * 1000;
        } catch (IOException e) {
            return false;
        }
    }

    private static void releaseFileLock(Path lockFile) {
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException ignored) {}
    }

    /** 进程内锁包裹一次操作。 */
    private <T> T withLock(String recipient, ThrowingSupplier<T> op) {
        ReentrantLock lock = inProcessLocks.computeIfAbsent(recipient, k -> new ReentrantLock());
        lock.lock();
        try {
            return op.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted in mailbox operation");
        } finally {
            lock.unlock();
        }
    }

    /** 进程内锁包裹一次 void 操作。 */
    private void withLockVoid(String recipient, ThrowingRunnable op) {
        ReentrantLock lock = inProcessLocks.computeIfAbsent(recipient, k -> new ReentrantLock());
        lock.lock();
        try {
            op.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted in mailbox operation");
        } finally {
            lock.unlock();
        }
    }

    /** 文件锁包裹一次操作（acquire → run → release）。 */
    private static void withFileLock(Path lockFile, Runnable op) {
        try {
            acquireFileLock(lockFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted acquiring mailbox lock");
        }
        try {
            op.run();
        } finally {
            releaseFileLock(lockFile);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws InterruptedException;
    }

    private interface ThrowingRunnable {
        void run() throws InterruptedException;
    }
}
