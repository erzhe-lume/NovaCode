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
        return "Replace an exact string in a file. old_string must appear exactly once. "
                + "Always use this instead of sed/awk via Bash. "
                + "MUST first read the file with ReadFile before calling — guesswork against a stale old_string will fail.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of("type", "string", "description", "Path to edit"),
                "old_string", Map.of("type", "string", "description", "Exact string to replace (must be unique)"),
                "new_string", Map.of("type", "string", "description", "Replacement string")
            ),
            "required", List.of("file_path", "old_string", "new_string")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = ReadFileTool.str(args, "file_path", "");
        String oldStr = ReadFileTool.str(args, "old_string", "");
        String newStr = ReadFileTool.str(args, "new_string", "");
        if (filePath.isEmpty()) return ToolResult.error("Error: file_path is required");

        Path path = ReadFileTool.resolve(cwd, filePath);

        // Read-before-edit check
        if (fileStateCache != null) {
            String err = fileStateCache.validate(path.toAbsolutePath().toString());
            if (err != null) return ToolResult.error(err);
        }

        if (!Files.exists(path)) return ToolResult.error("Error: file not found: " + filePath);

        String content;
        try { content = Files.readString(path); }
        catch (IOException e) { return ToolResult.error("Error reading file: " + e.getMessage()); }

        int count = countOccurrences(content, oldStr);
        if (count == 0) return ToolResult.error("Error: old_string not found in file");
        if (count > 1) return ToolResult.error("Error: old_string found " + count + " times, must be unique. Provide more context.");

        String newContent = content.replace(oldStr, newStr);
        try { Files.writeString(path, newContent); }
        catch (IOException e) { return ToolResult.error("Error writing file: " + e.getMessage()); }

        if (fileStateCache != null) fileStateCache.update(path.toAbsolutePath().toString());

        String diff = DiffUtil.buildDiff(content, newContent);
        return ToolResult.success("Updated " + filePath + "\n" + diff);
    }

    private static int countOccurrences(String text, String sub) {
        if (sub.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
