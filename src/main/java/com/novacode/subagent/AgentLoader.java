package com.novacode.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.novacode.permission.PermissionMode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载子 Agent 角色定义（第 13 章 F3），三级来源按优先级（后者覆盖前者）：
 * <ol>
 *   <li>内置 spec（{@link SubAgentSpec#GENERAL_PURPOSE} 等）</li>
 *   <li>用户级 {@code ~/.novacode/agents/*.md}</li>
 *   <li>项目级 {@code <projectRoot>/.novacode/agents/*.md}</li>
 * </ol>
 * 每个 {@code .md} 文件可选 YAML frontmatter（{@code ---} 分隔）+ Markdown 正文（成为系统提示）。
 * 解析失败的文件静默跳过（对照参考实现，不中断启动）。
 */
public final class AgentLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Map<String, SubAgentSpec> agents = new LinkedHashMap<>();

    private AgentLoader() {}

    public static Map<String, SubAgentSpec> loadAll(Path projectRoot) {
        var loader = new AgentLoader();
        loader.loadBuiltins();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            loader.loadDir(Path.of(home, ".novacode", "agents"));
        }
        if (projectRoot != null) {
            loader.loadDir(projectRoot.resolve(".novacode").resolve("agents"));
        }
        return Collections.unmodifiableMap(loader.agents);
    }

    /** 排序返回所有角色名（供 schema 枚举与描述使用）。 */
    public static List<String> listNames(Map<String, SubAgentSpec> agents) {
        var names = new ArrayList<>(agents.keySet());
        Collections.sort(names);
        return names;
    }

    private void loadBuiltins() {
        agents.put(SubAgentSpec.GENERAL_PURPOSE.name(), SubAgentSpec.GENERAL_PURPOSE);
        agents.put(SubAgentSpec.PLAN.name(), SubAgentSpec.PLAN);
        agents.put(SubAgentSpec.EXPLORE.name(), SubAgentSpec.EXPLORE);
    }

    private void loadDir(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) continue;
                try {
                    SubAgentSpec spec = parseAgentFile(path);
                    agents.put(spec.name(), spec);
                } catch (Exception e) {
                    // 坏文件静默跳过
                }
            }
        } catch (IOException ignored) {
            // 目录不可读，跳过
        }
    }

    /** 解析单个角色定义文件：可选 frontmatter + 正文（成为系统提示）。 */
    static SubAgentSpec parseAgentFile(Path path) throws IOException {
        String content = Files.readString(path).strip();
        String yamlBlock = null;
        String body = content;

        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end >= 0) {
                yamlBlock = content.substring(3, end).strip();
                body = content.substring(end + 3).strip();
            }
        }

        String name = null;
        String description = null;
        String model = null;
        List<String> tools = List.of();
        List<String> disallowedTools = List.of();
        int maxTurns = 0;
        PermissionMode permissionMode = null;
        Isolation isolation = Isolation.NONE;

        if (yamlBlock != null && !yamlBlock.isEmpty()) {
            Map<String, Object> fm = YAML.readValue(yamlBlock, new TypeReference<Map<String, Object>>() {});
            if (fm != null) {
                name = getString(fm, "name");
                description = getString(fm, "description");
                tools = getStringList(fm, "tools");
                disallowedTools = getStringList(fm, "disallowedTools");
                model = getString(fm, "model");
                Object mt = fm.get("maxTurns");
                if (mt instanceof Number n) maxTurns = n.intValue();
                String pm = getString(fm, "permissionMode");
                if (pm != null && !pm.isBlank() && !"inherit".equalsIgnoreCase(pm.strip())) {
                    permissionMode = PermissionMode.parse(pm);
                }
                isolation = Isolation.parse(getString(fm, "isolation"));
            }
        }

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Agent definition %s: missing required field 'name'".formatted(path));
        }
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("Agent definition %s: missing required field 'description'".formatted(path));
        }
        // 标准化 "inherit"（忽略大小写）→ null；其余模型名原样透传给路由层。
        if (model != null) {
            model = model.strip();
            if (model.equalsIgnoreCase("inherit")) model = null;
        }

        String systemPrompt = body.isEmpty() ? null : body;
        return new SubAgentSpec(name, description, tools, disallowedTools, systemPrompt, maxTurns, model, permissionMode, isolation);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (Object item : list) {
                if (item instanceof String s) result.add(s);
            }
            return List.copyOf(result);
        }
        return List.of();
    }
}
