package com.novacode.tool.impl;

import com.novacode.tool.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ReadFileTool implements Tool {

    private FileStateCache fileStateCache;
    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "ReadFile"; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public String description() {
        return "Read a file with line numbers. Always use this instead of cat/head/tail/sed via Bash. "
                + "Use absolute file paths. For large files, read in chunks with offset/limit. "
                + "MUST call this before editing a file with EditFile.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of("type", "string", "description", "Path to the file"),
                "offset", Map.of("type", "integer", "description", "Start line (0-based)", "default", 0),
                "limit", Map.of("type", "integer", "description", "Max lines to read", "default", 2000)
            ),
            "required", List.of("file_path")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = str(args, "file_path", "");
        if (filePath.isEmpty()) return ToolResult.error("Error: file_path is required");

        int offset = integer(args, "offset", 0);
        int limit = integer(args, "limit", 2000);
        Path path = resolve(cwd, filePath);

        if (!Files.exists(path)) return ToolResult.error("Error: file not found: " + filePath);
        if (Files.isDirectory(path)) return ToolResult.error("Error: not a file: " + filePath);

        String content;
        try { content = decode(Files.readAllBytes(path)); }
        catch (IOException e) { return ToolResult.error("Error reading file: " + e.getMessage()); }

        String[] lines = content.split("\n", -1);
        if (offset >= lines.length) return ToolResult.success("");

        int end = Math.min(offset + limit, lines.length);
        var sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (i > offset) sb.append('\n');
            sb.append(i + 1).append('\t').append(lines[i]);
        }

        if (fileStateCache != null) {
            try {
                fileStateCache.record(path.toAbsolutePath().toString(),
                    Files.getLastModifiedTime(path).toMillis());
            } catch (IOException ignored) {}
        }
        return ToolResult.success(sb.toString());
    }

    /** Decode file bytes: strip a UTF-8 BOM, try strict UTF-8, then fall back to
     *  GBK (common on Chinese Windows) so non-UTF-8 files don't garble. */
    private static String decode(byte[] bytes) throws CharacterCodingException {
        byte[] data = bytes;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            data = Arrays.copyOfRange(data, 3, data.length);
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("GBK"));
        }
    }

    /** 相对路径基于 cwd 解析并规范化为绝对路径（第 14 章 F5）。 */
    static Path resolve(Path cwd, String raw) {
        Path p = Path.of(raw);
        return p.isAbsolute() ? p.normalize() : cwd.resolve(p).normalize();
    }

    static String str(Map<String, Object> args, String key, String def) {
        var v = args.get(key); return v instanceof String s ? s : def;
    }
    static int integer(Map<String, Object> args, String key, int def) {
        var v = args.get(key); return v instanceof Number n ? n.intValue() : def;
    }
}
