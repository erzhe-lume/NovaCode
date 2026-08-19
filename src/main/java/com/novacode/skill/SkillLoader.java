package com.novacode.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.novacode.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 三级扫描 + frontmatter 解析（第 11 章 spec F2）。
 *
 * <p>内置 → 用户目录 → 项目目录，同名按优先级覆盖（后者覆盖前者）。单个文件解析失败
 * 只跳过该文件并记录警告，不阻断其余（N4）。目录型 Skill 读取入口 {@code SKILL.md} 与
 * {@code tools/*.json} + {@code tools/*.sh} 专属工具（F8）。</p>
 */
public class SkillLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final List<String> warnings = new ArrayList<>();

    public List<String> warnings() { return List.copyOf(warnings); }

    /** 三级合并加载。 */
    public Map<String, Skill> load(List<Skill> builtin, Path userDir, Path projectDir) {
        Map<String, Skill> result = new LinkedHashMap<>();
        for (Skill s : builtin) result.put(s.name(), s);
        merge(result, scanDir(userDir));
        merge(result, scanDir(projectDir));
        return result;
    }

    private static void merge(Map<String, Skill> result, Map<String, Skill> dir) {
        result.putAll(dir); // 同名后者覆盖
    }

    /** 扫描一个技能目录：单文件 {@code *.md} 与目录型 {@code /SKILL.md}（目录入口）。 */
    private Map<String, Skill> scanDir(Path dir) {
        Map<String, Skill> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.sorted().toList()) {
                Skill s = null;
                if (Files.isDirectory(p)) {
                    s = loadDirectorySkill(p);
                } else if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md")) {
                    s = loadFileSkill(p);
                }
                if (s != null) out.put(s.name(), s);
            }
        } catch (IOException ignored) {
            // 目录读取失败：返回已收集的部分
        }
        return out;
    }

    private Skill loadFileSkill(Path file) {
        try {
            return parse(file, Files.readString(file, StandardCharsets.UTF_8), List.of());
        } catch (Exception e) {
            warnings.add("跳过 Skill " + file + ": " + e.getMessage());
            return null;
        }
    }

    private Skill loadDirectorySkill(Path dir) {
        Path entry = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(entry)) return null;
        try {
            return parse(entry, Files.readString(entry, StandardCharsets.UTF_8),
                    loadDedicatedTools(dir.resolve("tools")));
        } catch (Exception e) {
            warnings.add("跳过 Skill " + dir + ": " + e.getMessage());
            return null;
        }
    }

    private List<Tool> loadDedicatedTools(Path toolsDir) {
        if (toolsDir == null || !Files.isDirectory(toolsDir)) return List.of();
        List<Tool> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(toolsDir)) {
            for (Path json : entries.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String fileName = json.getFileName().toString();
                String base = fileName.substring(0, fileName.length() - ".json".length());
                Path script = toolsDir.resolve(base + ".sh");
                if (!Files.isRegularFile(script)) continue;
                try {
                    JsonNode root = JSON.readTree(Files.readString(json, StandardCharsets.UTF_8));
                    String name = root.path("name").asText(base);
                    String desc = root.path("description").asText("");
                    Map<String, Object> schema = root.has("input_schema")
                            ? JSON.convertValue(root.get("input_schema"), MAP_TYPE)
                            : Map.of("type", "object", "properties", Map.of());
                    out.add(new ScriptTool(name, desc, schema, script.toString()));
                } catch (Exception e) {
                    warnings.add("跳过工具 " + json + ": " + e.getMessage());
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** 解析 frontmatter + 正文；非法（缺 name/description、YAML 坏）抛异常。 */
    private Skill parse(Path file, String raw, List<Tool> dedicatedTools) {
        String[] parts = splitFrontmatter(raw);
        Map<String, Object> meta;
        try {
            meta = parts[0] == null ? Map.of() : YAML.readValue(parts[0], MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("frontmatter YAML 解析失败: " + e.getMessage());
        }

        String name = str(meta, "name");
        String description = str(meta, "description");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("缺 name");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("缺 description");

        String model = str(meta, "model");
        return new Skill(
                name.toLowerCase(),
                description,
                strList(meta, "tools"),
                SkillMode.parse(str(meta, "mode")),
                integer(meta, "history", 0),
                model == null ? "" : model,
                parts[1] == null ? "" : parts[1],
                dedicatedTools);
    }

    /** 把原文拆成 [frontmatter, body]；无 frontmatter 时首元素为 null。 */
    static String[] splitFrontmatter(String raw) {
        if (raw == null) return new String[] { null, "" };
        String stripped = raw.stripLeading();
        if (!stripped.startsWith("---")) return new String[] { null, raw };
        String[] lines = raw.split("\n", -1);
        int start = 0;
        while (start < lines.length && !lines[start].strip().equals("---")) start++;
        int end = start + 1;
        while (end < lines.length && !lines[end].strip().equals("---")) end++;
        if (end >= lines.length) throw new IllegalArgumentException("frontmatter 未闭合");
        String yaml = String.join("\n", Arrays.copyOfRange(lines, start + 1, end));
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length));
        return new String[] { yaml, body };
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(String.valueOf(o).trim());
            return out;
        }
        return List.of(String.valueOf(v).trim());
    }

    private static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
