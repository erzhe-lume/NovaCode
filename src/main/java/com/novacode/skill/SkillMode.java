package com.novacode.skill;

/** Skill 执行模式（第 11 章 spec F5）。shared 共享主对话；independent 开独立对话回流摘要。 */
public enum SkillMode {
    SHARED,
    INDEPENDENT;

    /** 解析 frontmatter 的 mode 字段；未知 / 缺失 → SHARED（不抛异常）。 */
    public static SkillMode parse(String s) {
        if (s == null) return SHARED;
        return switch (s.trim().toLowerCase()) {
            case "independent", "isolated" -> INDEPENDENT;
            default -> SHARED;
        };
    }
}
