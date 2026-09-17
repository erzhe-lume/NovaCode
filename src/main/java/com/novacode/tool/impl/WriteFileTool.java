package com.novacode.tool.impl;

import com.novacode.tool.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class WriteFileTool implements Tool {

    private FileStateCache fileStateCache;
    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "WriteFile"; }
    @Override public ToolCategory category() { return ToolCategory.WRITE; }
    @Override public String description() {
        return "Write content to a file, creating parent directories. Overwrites existing files. "
                + "Always use this instead of echo/cat heredoc via Bash. "
                + "If the file already exists, read it with ReadFile first before overwriting. "
                + "Prefer EditFile for changing existing files (smaller, safer diff); "
                + "use WriteFile for new files or full rewrites.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of("type", "string", "description", "Path to the file"),
                "content", Map.of("type", "string", "description", "Content to write")
            ),
            "required", List.of("file_path", "content")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = ReadFileTool.str(args, "file_path", "");
        String content = ReadFileTool.str(args, "content", "");
        if (filePath.isEmpty()) return ToolResult.error("Error: file_path is required");

        Path path = ReadFileTool.resolve(cwd, filePath);

        // Read-before-write check (skip for new files)
        if (fileStateCache != null && Files.exists(path)) {
            String err = fileStateCache.validate(path.toAbsolutePath().toString());
            if (err != null) return ToolResult.error(err);
        }

        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, content);
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        if (fileStateCache != null) fileStateCache.update(path.toAbsolutePath().toString());
        return ToolResult.success("Successfully wrote to " + filePath);
    }
}
