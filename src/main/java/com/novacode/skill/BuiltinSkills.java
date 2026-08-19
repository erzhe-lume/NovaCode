package com.novacode.skill;

import java.util.List;

/** 内置三个样板 Skill（第 11 章 spec F7）：commit / review / test，均为 shared 模式。 */
public final class BuiltinSkills {

    private BuiltinSkills() {}

    public static List<Skill> samples() {
        return List.of(commit(), review(), test());
    }

    private static Skill commit() {
        return Skill.of("commit", "生成符合规范的 commit message 并提交",
                List.of("Bash", "ReadFile", "Grep"), SkillMode.SHARED, 0, "", """
                你负责生成并提交代码变更。执行步骤：
                1. 用 Bash 运行 `git status` 与 `git diff` 查看当前改动。
                2. 用 ReadFile 阅读有改动的文件，理解变更意图。
                3. 按 Conventional Commits 规范写一条简洁的中文 commit message（类型 + 一句话 + 必要的 body）。
                4. 用 Bash 执行 `git add -A && git commit -m "..."` 完成提交，并汇报结果。

                范围：{args}""");
    }

    private static Skill review() {
        return Skill.of("review", "对代码改动做一次审查，给出修改建议",
                List.of("ReadFile", "Grep", "Glob"), SkillMode.SHARED, 0, "", """
                你负责做一次代码审查。执行步骤：
                1. 用 Glob / Grep 定位相关文件，用 ReadFile 阅读代码。
                2. 重点找潜在 bug、安全问题与可简化的地方。
                3. 输出结构化审查结论，每条附 file_path:line 与具体修改建议。

                范围：{args}""");
    }

    private static Skill test() {
        return Skill.of("test", "运行测试并如实汇报结果",
                List.of("Bash", "ReadFile"), SkillMode.SHARED, 0, "", """
                你负责运行测试并汇报结果。执行步骤：
                1. 用 ReadFile 了解项目构建配置，确认测试命令。
                2. 用 Bash 运行测试（如 `mvn test`）。
                3. 如实汇报通过 / 失败；失败时贴出关键输出并定位原因。

                范围：{args}""");
    }
}
