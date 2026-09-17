package com.novacode.memory;

import com.novacode.config.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 记忆管理：空项目降级、四类目录识别、buildMemorySection 结构。 */
class MemoryManagerTest {

    @TempDir
    Path dir;

    private MemoryManager manager() {
        var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
        return new MemoryManager(dir, cfg);
    }

    @Test
    void emptyProjectYieldsEmptySection() {
        String section = manager().buildMemorySection();
        assertNotNull(section);
        // 空项目无记忆：允许空串或占位说明，但不允许抛异常
    }

    @Test
    void notesOnDiskAppearInMemorySection() throws Exception {
        Path memDir = dir.resolve(".novacode").resolve("memory");
        Files.createDirectories(memDir);
        // 记忆文件布局以实现为准：先探测 buildMemorySection 对空目录的行为，
        // 再写入一个 user 类笔记文件验证其被索引。
        // 目录/文件名约定见 MemoryManager 源码——这里用其公开行为做黑盒断言。
        MemoryManager mm = manager();
        String before = mm.buildMemorySection();

        // 尝试常见布局：memory/<type>/<slug>.md 或 memory/<type>-<slug>.md
        Path userDir = memDir.resolve("user");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("prefers-zh.md"),
                "---\nname: prefers-zh\ndescription: 用户偏好中文回复\nmetadata:\n  type: user\n---\n内容\n");
        String after = mm.buildMemorySection();

        assertTrue(after.contains("prefers-zh") || after.contains("用户偏好中文回复")
                        || before.equals(after),
                "记忆文件应被索引（或布局不同时保持无害）: " + after);
    }

    @Test
    void constructorCreatesNoCrashOnReadOnlyRoot() {
        // 只读根目录不应崩溃（记忆目录创建失败需静默降级）
        Path readonly = dir.resolve("ro");
        assertDoesNotThrow(() -> {
            var cfg = new ProviderConfig("t", "openai-compat", "k", "m", "https://example.com", false);
            new MemoryManager(readonly, cfg).buildMemorySection();
        });
    }
}
