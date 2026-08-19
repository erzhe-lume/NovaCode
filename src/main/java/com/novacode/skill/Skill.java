package com.novacode.skill;

import com.novacode.tool.Tool;

import java.util.List;

/**
 * 单个 Skill（第 11 章 spec F1）：YAML frontmatter 的元信息 + Markdown 正文 SOP。
 *
 * @param name           唯一名字（已小写）
 * @param description    一句说明（菜单用）
 * @param tools          工具白名单；空列表 = 不收窄（可用全部工具）
 * @param mode           shared / independent
 * @param history        independent 模式带多少条近期历史
 * @param model          可选指定模型；空串 = 无
 * @param body           SOP 正文（含 {@code {args}} 占位符）
 * @param dedicatedTools 目录型 Skill 的专属工具；空 = 无
 */
public record Skill(
        String name,
        String description,
        List<String> tools,
        SkillMode mode,
        int history,
        String model,
        String body,
        List<Tool> dedicatedTools) {

    public Skill {
        tools = tools == null ? List.of() : List.copyOf(tools);
        dedicatedTools = dedicatedTools == null ? List.of() : List.copyOf(dedicatedTools);
        mode = mode == null ? SkillMode.SHARED : mode;
    }

    /** 单文件 Skill 便捷工厂（无专属工具）。 */
    public static Skill of(String name, String description, List<String> tools,
                           SkillMode mode, int history, String model, String body) {
        return new Skill(name, description, tools, mode, history, model, body, List.of());
    }
}
