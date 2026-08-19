package com.novacode.tool.impl;

import com.novacode.tool.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GrepTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache", "target", ".idea", "build"
    );
    private static final int MAX_MATCH_CHARS = 10_000;

    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "Grep"; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public String description() {
        return "Search file contents with regex. Returns file:line:content. Skips binary files and .git/node_modules/etc. "
                + "Always use this instead of grep or rg via Bash.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string", "description", "Regex pattern to search for"),
                "path", Map.of("type", "string", "description", "Base directory", "default", "."),
                "include", Map.of("type", "string", "description", "Glob filename filter (e.g. '*.java')")
            ),
            "required", List.of("pattern")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = ReadFileTool.str(args, "pattern", "");
        String basePath = ReadFileTool.str(args, "path", ".");
        String include = ReadFileTool.str(args, "include", "");
        if (pattern.isEmpty()) return ToolResult.error("Error: pattern is required");

        Path root = ReadFileTool.resolve(cwd, basePath);
        if (!Files.exists(root) || !Files.isDirectory(root))
            return ToolResult.error("Error: path not found: " + basePath);

        Pattern regex;
        try { regex = Pattern.compile(pattern); }
        catch (PatternSyntaxException e) { return ToolResult.error("Error: invalid regex: " + e.getMessage()); }

        PathMatcher includeMatcher;
        if (include.isEmpty()) {
            includeMatcher = null;
        } else {
            try {
                includeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + include);
            } catch (PatternSyntaxException e) {
                return ToolResult.error("Error: invalid include glob: " + e.getMessage());
            }
        }

        var files = new ArrayList<Path>();
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
                    if (includeMatcher != null && !includeMatcher.matches(file.getFileName()))
                        return FileVisitResult.CONTINUE;
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { return ToolResult.error("Error: " + e.getMessage()); }

        Collections.sort(files);

        var results = new ArrayList<String>();
        int totalChars = 0;
        for (Path file : files) {
            if (isBinary(file)) continue;
            try (BufferedReader r = Files.newBufferedReader(file)) {
                String line; int n = 0;
                while ((line = r.readLine()) != null) {
                    n++;
                    if (regex.matcher(line).find()) {
                        String entry = root.relativize(file) + ":" + n + ":" + line;
                        totalChars += entry.length() + 1;
                        if (totalChars > MAX_MATCH_CHARS) {
                            results.add("... truncated (" + MAX_MATCH_CHARS + " chars)");
                            return ToolResult.success(String.join("\n", results));
                        }
                        results.add(entry);
                    }
                }
            } catch (IOException ignored) {}
        }

        if (results.isEmpty()) return ToolResult.success("No matches found.");
        return ToolResult.success(String.join("\n", results));
    }

    private static boolean isBinary(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[512];
            int n = is.read(buf);
            for (int i = 0; i < n; i++) if (buf[i] == 0) return true;
            return false;
        } catch (IOException e) { return true; }
    }
}
