package com.novacode.tool.impl;

import com.novacode.tool.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditFileTool implements Tool {

    private FileStateCache fileStateCache;
    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "EditFile"; }
    @Override public ToolCategory category() { return ToolCategory.WRITE; }
    @Override public String description() {
        return "Replace an exact string in a file. old_string must appear exactly once unless replace_all is true. "
                + "Always use this instead of sed/awk via Bash. "
                + "MUST first read the file with ReadFile before calling — guesswork against a stale old_string will fail. "
                + "Set replace_all=true to replace every occurrence (e.g. renaming a variable). "
                + "For several disjoint edits in one file, make multiple EditFile calls in the same batch; "
                + "for wholesale rewrites, use WriteFile instead.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of("type", "string", "description", "Path to edit"),
                "old_string", Map.of("type", "string", "description", "Exact string to replace (must be unique unless replace_all)"),
                "new_string", Map.of("type", "string", "description", "Replacement string"),
                "replace_all", Map.of("type", "boolean", "description", "Replace every occurrence (default false)", "default", false)
            ),
            "required", List.of("file_path", "old_string", "new_string")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = ReadFileTool.str(args, "file_path", "");
        String oldStr = ReadFileTool.str(args, "old_string", "");
        String newStr = ReadFileTool.str(args, "new_string", "");
        boolean replaceAll = bool(args, "replace_all");
        if (filePath.isEmpty()) return ToolResult.error("Error: file_path is required");
        if (oldStr.isEmpty()) return ToolResult.error("Error: old_string is required");

        Path path = ReadFileTool.resolve(cwd, filePath);

        // Read-before-edit check
        if (fileStateCache != null) {
            String err = fileStateCache.validate(path.toAbsolutePath().toString());
            if (err != null) return ToolResult.error(err);
        }

        if (!Files.exists(path)) return ToolResult.error("Error: file not found: " + filePath);

        // 按探测到的编码读，写回同一编码（GBK 文件编辑不再被静默转成 UTF-8），BOM 保留。
        ReadFileTool.Decoded decoded;
        try { decoded = ReadFileTool.readDetected(path); }
        catch (IOException e) { return ToolResult.error("Error reading file: " + e.getMessage()); }
        String content = decoded.content();

        int count = countOccurrences(content, oldStr);
        if (count == 0) return ToolResult.error("Error: old_string not found in file");
        if (count > 1 && !replaceAll) {
            return ToolResult.error("Error: old_string found " + count
                    + " times, must be unique. Provide more context, or set replace_all=true to replace every occurrence.");
        }

        String newContent = replaceAll && count > 1
                ? content.replace(oldStr, newStr)
                : content.replaceFirst(java.util.regex.Pattern.quote(oldStr),
                        java.util.regex.Matcher.quoteReplacement(newStr));
        try {
            Files.writeString(path, (decoded.bom() ? "\uFEFF" : "") + newContent, decoded.charset());
        }
        catch (IOException e) { return ToolResult.error("Error writing file: " + e.getMessage()); }

        if (fileStateCache != null) fileStateCache.update(path.toAbsolutePath().toString());

        String diff = DiffUtil.buildDiff(content, newContent);
        return ToolResult.success("Updated " + filePath
                + (count > 1 ? " (" + count + " occurrences)" : "") + "\n" + diff);
    }

    private static boolean bool(Map<String, Object> args, String key) {
        var v = args.get(key);
        return v instanceof Boolean b && b;
    }

    private static int countOccurrences(String text, String sub) {
        if (sub.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
