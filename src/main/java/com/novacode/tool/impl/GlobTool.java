package com.novacode.tool.impl;

import com.novacode.tool.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class GlobTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache", "target", ".idea", "build"
    );

    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "Glob"; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public String description() {
        return "Find files matching a glob pattern (**/*.py, src/**/*.ts). Skips .git/node_modules/etc. Sorted by mtime. "
                + "Always use this instead of find or ls via Bash. "
                + "Use when you know or can guess the filename/extension (e.g. '**/*Tool.java' to map the codebase); "
                + "use Grep instead when you know content that must appear inside the file.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string", "description", "Glob pattern (e.g. '**/*.java')"),
                "path", Map.of("type", "string", "description", "Base directory", "default", ".")
            ),
            "required", List.of("pattern")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = ReadFileTool.str(args, "pattern", "");
        String basePath = ReadFileTool.str(args, "path", ".");
        if (pattern.isEmpty()) return ToolResult.error("Error: pattern is required");

        Path root = ReadFileTool.resolve(cwd, basePath);
        if (!Files.exists(root) || !Files.isDirectory(root))
            return ToolResult.error("Error: path not found: " + basePath);

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            return ToolResult.error("Error: invalid glob pattern: " + e.getMessage());
        }
        // Java glob 的 `**/` 不能匹配零层目录（`**/*.java` 匹配不了根下文件，
        // `src/**/*.java` 匹配不了 src 直下文件），补充两个符合直觉的降级 matcher：
        PathMatcher leadingFallback = pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;
        PathMatcher collapsed = pattern.contains("/**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace("/**/", "/"))
                : null;
        var matches = new ArrayList<String>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString()))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = root.relativize(file);
                    boolean hit = matcher.matches(file.getFileName()) || matcher.matches(rel)
                            || (leadingFallback != null
                                && (leadingFallback.matches(rel) || leadingFallback.matches(file.getFileName())))
                            || (collapsed != null && collapsed.matches(rel));
                    if (hit) matches.add(rel.toString());
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        matches.sort((a, b) -> {
            try {
                return Long.compare(
                    Files.getLastModifiedTime(root.resolve(b)).toMillis(),
                    Files.getLastModifiedTime(root.resolve(a)).toMillis());
            } catch (IOException e) { return a.compareTo(b); }
        });

        if (matches.isEmpty()) return ToolResult.success("No files matched.");
        return ToolResult.success(String.join("\n", matches));
    }
}
