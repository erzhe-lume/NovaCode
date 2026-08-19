package com.novacode.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 三层项目指令文件加载（第 9 章 F1/F2）。
 *
 * <p>按优先级从高到低读取三份手写 Markdown：项目根 {@code AGENTS.md} →
 * {@code .mewcode/AGENTS.md} → {@code ~/.mewcode/AGENTS.md}，拼接后注入
 * System Prompt 的 customInstructions 槽。每份文件支持 {@code @include} 引用，
 * 展开时带三个护栏：嵌套深度上限、visited 集合防循环、拦截跳出项目目录的路径。</p>
 */
public final class InstructionLoader {

    private InstructionLoader() {}

    /** @include 最大嵌套深度 */
    public static final int MAX_INCLUDE_DEPTH = 5;

    /** 各层指令文件名 */
    private static final String INSTRUCTION_FILE = "AGENTS.md";

    /** 一个已加载的指令文件源 */
    public record InstructionSource(String path, String content) {}

    /**
     * 加载并拼接三层指令文件。高优先级排前面；无任何文件时返回空串。
     */
    public static String loadInstructions(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path userMewcode = Path.of(System.getProperty("user.home"), ".mewcode")
                .toAbsolutePath().normalize();
        List<Path> allowedRoots = List.of(root, userMewcode);

        List<Path> layers = List.of(
                root.resolve(INSTRUCTION_FILE),
                root.resolve(".mewcode").resolve(INSTRUCTION_FILE),
                userMewcode.resolve(INSTRUCTION_FILE));

        var sources = new ArrayList<InstructionSource>();
        var seen = new HashSet<String>();
        for (Path layer : layers) {
            addSource(sources, seen, layer, allowedRoots);
        }
        if (sources.isEmpty()) {
            return "";
        }

        var parts = new ArrayList<String>();
        for (var s : sources) {
            parts.add("Contents of %s:\n\n%s".formatted(label(s.path(), root), s.content()));
        }
        return String.join("\n\n---\n\n", parts);
    }

    /** 显示尽量简洁的路径标签：项目内用相对路径，其余用绝对路径。 */
    private static String label(String path, Path root) {
        try {
            Path rel = root.relativize(Path.of(path));
            if (!rel.toString().startsWith("..")) {
                return rel.toString();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return path;
    }

    /** 读取一个指令文件，成功则展开 @include 后加入列表；已读过的路径不重复加入。 */
    private static void addSource(List<InstructionSource> out, Set<String> seen,
                                  Path path, List<Path> allowedRoots) {
        String abs = path.toAbsolutePath().normalize().toString();
        if (seen.contains(abs)) {
            return;
        }
        String content;
        try {
            content = Files.readString(Path.of(abs));
        } catch (IOException e) {
            return;
        }
        seen.add(abs);
        String expanded = expandIncludes(content, Path.of(abs).getParent().toString(),
                seen, 0, allowedRoots);
        out.add(new InstructionSource(abs, expanded));
    }

    /**
     * 递归展开文本中的 {@code @include} 指令。代码块（```）内不展开；
     * 同一路径不重复包含（防循环）；超深与越界路径保留原始行。
     */
    static String expandIncludes(String content, String baseDir, Set<String> seen,
                                 int depth, List<Path> allowedRoots) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return content;
        }
        var out = new StringBuilder();
        boolean inCode = false;
        try (var reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) {
                    inCode = !inCode;
                    out.append(line).append('\n');
                    continue;
                }
                if (!inCode) {
                    String includePath = parseInclude(trimmed);
                    if (includePath != null) {
                        String resolved = resolveInclude(includePath, baseDir);
                        if (resolved != null && isAllowed(resolved, allowedRoots)) {
                            String absPath = Path.of(resolved).toAbsolutePath().normalize().toString();
                            if (!seen.contains(absPath)) {
                                try {
                                    String data = Files.readString(Path.of(absPath));
                                    seen.add(absPath);
                                    out.append("<!-- included from %s -->\n".formatted(includePath));
                                    out.append(expandIncludes(data,
                                            Path.of(absPath).getParent().toString(),
                                            seen, depth + 1, allowedRoots));
                                    out.append('\n');
                                    continue;
                                } catch (IOException ignored) {
                                    // 包含失败保留原始行，让用户注意到
                                }
                            }
                        }
                        // 越界 / 已见 / 失败：保留原始行
                    }
                }
                out.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return content;
        }
        return out.toString();
    }

    /**
     * 解析 {@code @include} 行。仅识别 {@code @./}、{@code @../}、{@code @~/}、
     * {@code @/} 前缀；其它 {@code @token}（如 @username）返回 null 避免误判。
     */
    static String parseInclude(String trimmed) {
        if (!trimmed.startsWith("@") || trimmed.startsWith("@@")) {
            return null;
        }
        String rest = trimmed.substring(1);
        if (rest.isEmpty() || rest.contains(" ") || rest.contains("\t")) {
            return null;
        }
        if (rest.startsWith("./") || rest.startsWith("../")
                || rest.startsWith("~/") || rest.startsWith("/")) {
            return rest;
        }
        return null;
    }

    /** 将 @include 路径解析为绝对路径字符串；无法解析返回 null。 */
    static String resolveInclude(String p, String baseDir) {
        if (p.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) {
                return null;
            }
            return Path.of(home, p.substring(2)).toAbsolutePath().normalize().toString();
        }
        Path path = Path.of(p);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize().toString();
        }
        return Path.of(baseDir, p).toAbsolutePath().normalize().toString();
    }

    /** 路径护栏：目标必须落在允许根（项目根或 ~/.mewcode/）之内。 */
    private static boolean isAllowed(String abs, List<Path> allowedRoots) {
        Path p = Path.of(abs);
        for (Path root : allowedRoots) {
            if (p.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
