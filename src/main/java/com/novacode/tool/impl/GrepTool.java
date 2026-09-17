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
    private static final int DEFAULT_FILE_LIMIT = 100;
    private static final int DEFAULT_CONTENT_LIMIT = 1000;

    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "Grep"; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public String description() {
        return "Search file contents with regex. Skips binary files and .git/node_modules/etc. "
                + "Always use this instead of grep or rg via Bash. "
                + "output_mode=files_with_matches returns matching file paths only — "
                + "prefer it for a first pass over a large codebase, then ReadFile the interesting hits "
                + "(this avoids drowning in content-mode output). "
                + "output_mode=count returns per-file match counts. Default content mode prints file:line:content. "
                + "Use Glob instead when you know the filename but not the content.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string", "description", "Regex pattern to search for"),
                "path", Map.of("type", "string", "description", "Base directory", "default", "."),
                "include", Map.of("type", "string", "description", "Glob filename filter (e.g. '*.java')"),
                "output_mode", Map.of("type", "string",
                    "enum", List.of("content", "files_with_matches", "count"),
                    "description", "content=file:line:text, files_with_matches=paths only, count=per-file match counts",
                    "default", "content"),
                "head_limit", Map.of("type", "integer",
                    "description", "Max entries returned (files for path modes, matches for content)",
                    "default", 100)
            ),
            "required", List.of("pattern")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = ReadFileTool.str(args, "pattern", "");
        String basePath = ReadFileTool.str(args, "path", ".");
        String include = ReadFileTool.str(args, "include", "");
        String mode = ReadFileTool.str(args, "output_mode", "content");
        boolean filesMode = "files_with_matches".equals(mode);
        boolean countMode = "count".equals(mode);
        if (!filesMode && !countMode && !"content".equals(mode)) {
            return ToolResult.error("Error: unknown output_mode '" + mode
                    + "' (content | files_with_matches | count)");
        }
        int headLimit = ReadFileTool.integer(args, "head_limit",
                filesMode || countMode ? DEFAULT_FILE_LIMIT : DEFAULT_CONTENT_LIMIT);
        if (headLimit <= 0) headLimit = filesMode || countMode ? DEFAULT_FILE_LIMIT : DEFAULT_CONTENT_LIMIT;
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

        // 分批并行匹配：批内 parallelStream（多核读文件+正则），批间串行保序收集，
        // head_limit 达成即提前停止（最多多算一批）。
        var results = new ArrayList<String>();
        String truncatedNote = null;
        int totalChars = 0;
        final int BATCH = 64;
        for (int i = 0; i < files.size() && truncatedNote == null; i += BATCH) {
            var batch = files.subList(i, Math.min(i + BATCH, files.size()));
            var batchResults = batch.parallelStream()
                    .map(f -> matchOne(f, root, regex, filesMode, countMode))
                    .toList();
            for (int k = 0; k < batchResults.size() && truncatedNote == null; k++) {
                List<String> entries = batchResults.get(k);
                if (entries == null) continue;
                for (String entry : entries) {
                    if (results.size() >= headLimit) {
                        truncatedNote = "... limited to first " + headLimit
                                + (filesMode || countMode ? " files" : " matches");
                        break;
                    }
                    if (!filesMode && !countMode) {
                        totalChars += entry.length() + 1;
                        if (totalChars > MAX_MATCH_CHARS) {
                            truncatedNote = "... truncated (" + MAX_MATCH_CHARS + " chars)";
                            break;
                        }
                    }
                    results.add(entry);
                }
            }
        }

        if (results.isEmpty()) return ToolResult.success("No matches found.");
        return ToolResult.success(String.join("\n", results)
                + (truncatedNote != null ? "\n" + truncatedNote : ""));
    }

    /** content 模式单文件每文件条数上限（防单文件巨量匹配拖垮内存）。 */
    private static final int PER_FILE_MATCH_LIMIT = 200;

    /** 单文件匹配：files 模式返回 [path]、count 模式返回 [path:count]、content 模式返回
     *  [path:line:text...]；无匹配或读失败返回 null。 */
    private static List<String> matchOne(Path file, Path root, Pattern regex,
                                         boolean filesMode, boolean countMode) {
        if (isBinary(file)) return null;
        String rel = String.valueOf(root.relativize(file));
        try (BufferedReader r = Files.newBufferedReader(file)) {
            if (filesMode) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (regex.matcher(line).find()) return List.of(rel);
                }
                return null;
            }
            if (countMode) {
                int count = 0;
                for (String line; (line = r.readLine()) != null; ) {
                    if (regex.matcher(line).find()) count++;
                }
                return count > 0 ? List.of(rel + ":" + count) : null;
            }
            var entries = new ArrayList<String>();
            int n = 0;
            for (String line; (line = r.readLine()) != null; ) {
                n++;
                if (regex.matcher(line).find()) {
                    entries.add(rel + ":" + n + ":" + line);
                    if (entries.size() >= PER_FILE_MATCH_LIMIT) break;
                }
            }
            return entries.isEmpty() ? null : entries;
        } catch (IOException e) {
            return null;
        }
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
