package com.novacode.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks ReadFile operations to enforce "read before write/edit".
 * Files must be read before they can be modified, and must not have changed
 * on disk since that read (staleness defense — external edits or Bash writes
 * invalidate the cached view).
 */
public class FileStateCache {
    private final ConcurrentHashMap<String, Long> lastRead = new ConcurrentHashMap<>();

    public void record(String absPath, long mtime) { lastRead.put(absPath, mtime); }

    public void update(String absPath) {
        try {
            long mtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
            lastRead.put(absPath, mtime);
        } catch (Exception ignored) {}
    }

    /** Returns error message if file hasn't been read, or was modified after the
     *  last read; null if OK to edit. File-missing等读取异常放行 —— 由工具层报 not found。 */
    public String validate(String absPath) {
        Long recorded = lastRead.get(absPath);
        if (recorded == null)
            return "Error: file must be read with ReadFile before editing: " + absPath;
        try {
            long current = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
            if (current != recorded)
                return "Error: file has been modified since last read (mtime changed) — "
                        + "ReadFile it again before editing: " + absPath;
        } catch (Exception ignored) {
        }
        return null;
    }
}
