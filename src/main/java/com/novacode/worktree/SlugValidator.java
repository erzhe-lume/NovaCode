package com.novacode.worktree;

import java.util.regex.Pattern;

/**
 * Worktree 目录名安全校验（第 14 章 F2）。LLM 输入的目录名先经这里清洗，
 * 防止路径穿越（`..`、绝对路径、空段）与非法字符（Windows 保留字符、`*`/`?` 等）。
 *
 * <p>规则：整体非空且 ≤ {@value #MAX_LENGTH}；以 `/` 分段（允许嵌套），每段非空、
 * 不等于 `.`/`..`、匹配 {@link #VALID_SEGMENT}；拒绝前导 `/` 或 `\`（绝对路径）。</p>
 */
public final class SlugValidator {

    public static final int MAX_LENGTH = 64;

    /** 合法段字符集：字母、数字、点、下划线、连字符。 */
    private static final Pattern VALID_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private SlugValidator() {}

    /**
     * @return null 表示通过；否则返回可读的错误信息
     */
    public static String validate(String name) {
        if (name == null) return "名称不能为空";
        String s = name.trim();
        if (s.isEmpty()) return "名称不能为空";
        if (s.length() > MAX_LENGTH) return "名称过长（>" + MAX_LENGTH + " 字符）";
        if (s.startsWith("/") || s.startsWith("\\")) return "名称不能是绝对路径";

        String[] segments = s.split("/", -1);
        for (String seg : segments) {
            if (seg.isEmpty()) return "名称含空路径段";
            if (seg.equals(".") || seg.equals("..")) return "名称含非法段 '.'/'..'";
            if (!VALID_SEGMENT.matcher(seg).matches()) return "名称含非法字符: " + seg;
        }
        return null;
    }

    /** 把嵌套路径扁平化为单一 token（`/` → `+`），供分支名使用。 */
    public static String flatten(String name) {
        return name.trim().replace('/', '+');
    }

    /** 由目录名推导分支名：{@code worktree-<扁平名>}。 */
    public static String branchName(String name) {
        return "worktree-" + flatten(name);
    }
}
