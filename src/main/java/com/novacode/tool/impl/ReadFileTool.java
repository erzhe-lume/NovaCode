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

    /** 单行截断阈值：防 minified / 超长单行文件灌爆上下文。 */
    private static final int MAX_LINE_CHARS = 500;
    /** 总输出截断阈值（字符）：约 7.5K token，超出提示用 offset 续读。 */
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private FileStateCache fileStateCache;
    private Path cwd = Path.of(System.getProperty("user.dir"));
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "ReadFile"; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public String description() {
        return "Read a file with line numbers. Always use this instead of cat/head/tail/sed via Bash. "
                + "For a first look at a large file, read the head (limit=80) to see its structure, "
                + "then target further chunks with offset/limit. "
                + "Output is capped: single lines over 500 chars and total output over ~30K chars are "
                + "truncated with an offset hint — continue reading with that offset instead of retrying blindly. "
                + "Also use this to re-read archived tool results mentioned in spill notices. "
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

        Decoded decoded;
        try { decoded = readDetected(path); }
        catch (IOException e) { return ToolResult.error("Error reading file: " + e.getMessage()); }

        String[] lines = decoded.content().split("\n", -1);
        if (offset >= lines.length) return ToolResult.success("");

        var sb = new StringBuilder();
        int end = Math.min(offset + limit, lines.length);
        boolean truncated = false;
        int i = offset;
        for (; i < end; i++) {
            String line = lines[i];
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + "…(行截断，原长 " + line.length() + " 字符)";
            }
            if (sb.length() + line.length() + 1 > MAX_OUTPUT_CHARS && i > offset) {
                truncated = true;
                end = i;
                break;
            }
            if (i > offset) sb.append('\n');
            sb.append(i + 1).append('\t').append(line);
        }
        if (truncated) {
            sb.append("\n…(输出已达 ").append(MAX_OUTPUT_CHARS)
              .append(" 字符上限被截断；用 offset=").append(end)
              .append(" 续读，文件共 ").append(lines.length).append(" 行)");
        }

        if (fileStateCache != null) {
            try {
                fileStateCache.record(path.toAbsolutePath().toString(),
                    Files.getLastModifiedTime(path).toMillis());
            } catch (IOException ignored) {}
        }
        return ToolResult.success(sb.toString());
    }

    /** 读取结果：内容 + 探测到的编码 + 是否有 UTF-8 BOM（写回时保持编码往返一致）。 */
    record Decoded(String content, Charset charset, boolean bom) {}

    /** 读文件并探测编码：strip BOM、严格 UTF-8、失败降级 GBK（写回用同一编码）。 */
    static Decoded readDetected(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        boolean bom = data.length >= 3
                && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF;
        if (bom) data = Arrays.copyOfRange(data, 3, data.length);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return new Decoded(decoder.decode(ByteBuffer.wrap(data)).toString(), StandardCharsets.UTF_8, bom);
        } catch (CharacterCodingException e) {
            Charset gbk = Charset.forName("GBK");
            return new Decoded(new String(data, gbk), gbk, bom);
        }
    }

    /** 字节 → 文本：严格 UTF-8 失败降级 GBK。供命令输出等无编码保证的字节流使用
     *  （中文 Windows 的 cmd.exe 默认输出 GBK，按 UTF-8 解码必然乱码）。 */
    static String decodeBytes(byte[] data) {
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
