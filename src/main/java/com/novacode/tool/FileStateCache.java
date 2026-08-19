package com.novacode.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks ReadFile operations to enforce "read before write/edit".
 * Files must be read before they can be modified.
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

    /** Returns error message if file hasn't been read, null if OK. */
    public String validate(String absPath) {
        if (!lastRead.containsKey(absPath))
            return "Error: file must be read with ReadFile before editing: " + absPath;
        return null;
    }
}
