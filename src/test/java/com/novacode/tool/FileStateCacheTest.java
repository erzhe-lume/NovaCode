package com.novacode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/** 读前编辑强制 + 过期视图防御（mtime 比对）。 */
class FileStateCacheTest {

    @TempDir
    Path dir;

    @Test
    void unreadFileRejected() {
        var cache = new FileStateCache();
        String err = cache.validate(dir.resolve("a.txt").toAbsolutePath().toString());
        assertNotNull(err);
        assertTrue(err.contains("must be read"), "错误应指引先 ReadFile: " + err);
    }

    @Test
    void readAndUnchangedPasses() throws Exception {
        Path f = dir.resolve("b.txt");
        Files.writeString(f, "content");
        var cache = new FileStateCache();
        cache.record(f.toAbsolutePath().toString(),
                Files.getLastModifiedTime(f).toMillis());
        assertNull(cache.validate(f.toAbsolutePath().toString()));
    }

    @Test
    void externallyModifiedAfterReadRejected() throws Exception {
        Path f = dir.resolve("c.txt");
        Files.writeString(f, "v1");
        var cache = new FileStateCache();
        cache.record(f.toAbsolutePath().toString(),
                Files.getLastModifiedTime(f).toMillis());

        // 外部改动（Bash / 编辑器 / 其他工具）
        Files.setLastModifiedTime(f, FileTime.fromMillis(
                Files.getLastModifiedTime(f).toMillis() + 5_000));

        String err = cache.validate(f.toAbsolutePath().toString());
        assertNotNull(err, "过期视图应被拒绝");
        assertTrue(err.contains("modified since last read"), "错误应说明文件已变: " + err);
    }

    @Test
    void updateAfterWriteRevalidates() throws Exception {
        Path f = dir.resolve("d.txt");
        Files.writeString(f, "v1");
        var cache = new FileStateCache();
        long mtime = Files.getLastModifiedTime(f).toMillis();
        cache.record(f.toAbsolutePath().toString(), mtime);
        // 显式改 mtime 模拟写入（同毫秒连续写在 NTFS 上可能 mtime 不变，不能依赖真实写）
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime + 5_000));
        assertNotNull(cache.validate(f.toAbsolutePath().toString()), "mtime 已变未 update 应拒绝");

        cache.update(f.toAbsolutePath().toString()); // 写后刷新
        assertNull(cache.validate(f.toAbsolutePath().toString()), "update 后应放行");
    }
}
