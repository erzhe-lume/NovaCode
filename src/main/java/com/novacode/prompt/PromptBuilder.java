package com.novacode.prompt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles a system prompt from prioritized sections.
 *
 * <p>Two entry points split stable from volatile content so the stable prefix
 * stays byte-identical across turns (F3 / N1):
 * <ul>
 *   <li>{@link #buildStableModules()} — the seven fixed modules only, no
 *       environment. This string is the cacheable prefix.</li>
 *   <li>{@link #buildSystemPrompt(EnvironmentContext, BuildOptions)} — stable
 *       modules + environment section + optional modules, the full system
 *       prompt sent to the API.</li>
 * </ul>
 */
public class PromptBuilder {

    // ── Inner types ─────────────────────────────────────────────────────

    public record Section(String name, int priority, String content) {}

    public record EnvironmentContext(
            String workDir,
            String os,
            String arch,
            String shell,
            boolean isGitRepo,
            String gitBranch,
            String appVersion,
            String model,
            String date) {}

    /**
     * Optional modules injected after the fixed ones. Skill / custom
     * instructions / long-term memory are reserved slots — this chapter keeps
     * them empty (F1 "不做的事").
     */
    public record BuildOptions(
            String skillSection,
            String customInstructions,
            String memorySection) {}

    // ── Builder state ───────────────────────────────────────────────────

    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));

        var parts = new ArrayList<String>();
        for (Section s : sections) {
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    // ── Static convenience methods ──────────────────────────────────────

    /** Detect the current runtime environment. */
    public static EnvironmentContext detectEnvironment(String model) {
        String workDir = System.getProperty("user.dir");
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown");
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "bash";
        }

        boolean isGitRepo = false;
        String gitBranch = "";

        try {
            Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if ("true".equals(line != null ? line.strip() : "")) {
                    isGitRepo = true;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // not a git repo or git not available — degrade silently (N4)
        }

        if (isGitRepo) {
            try {
                Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true)
                        .start();
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        gitBranch = line.strip();
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {
                // branch detection failed
            }
        }

        String date = LocalDate.now().toString();
        return new EnvironmentContext(workDir, osName, arch, shell, isGitRepo, gitBranch,
                "1.0.0", model, date);
    }

    /** The cacheable stable prefix: the seven fixed modules, no environment. */
    public static String buildStableModules() {
        var builder = new PromptBuilder();
        builder.add(PromptSections.identitySection());
        builder.add(PromptSections.systemSection());
        builder.add(PromptSections.doingTasksSection());
        builder.add(PromptSections.executingActionsSection());
        builder.add(PromptSections.usingToolsSection());
        builder.add(PromptSections.toneStyleSection());
        builder.add(PromptSections.outputEfficiencySection());
        return builder.build();
    }

    /** Full system prompt: stable modules + environment + optional modules. */
    public static String buildSystemPrompt(EnvironmentContext env, BuildOptions options) {
        var builder = new PromptBuilder();

        builder.add(PromptSections.identitySection());
        builder.add(PromptSections.systemSection());
        builder.add(PromptSections.doingTasksSection());
        builder.add(PromptSections.executingActionsSection());
        builder.add(PromptSections.usingToolsSection());
        builder.add(PromptSections.toneStyleSection());
        builder.add(PromptSections.outputEfficiencySection());
        builder.add(PromptSections.environmentSection(env));

        // Optional modules, priority 90 — added only when non-empty (F1)
        if (options != null) {
            if (options.skillSection() != null && !options.skillSection().isEmpty()) {
                builder.add(new Section("Skills", 90, options.skillSection()));
            }
            if (options.customInstructions() != null && !options.customInstructions().isEmpty()) {
                builder.add(new Section("CustomInstructions", 91, options.customInstructions()));
            }
            if (options.memorySection() != null && !options.memorySection().isEmpty()) {
                builder.add(new Section("Memory", 92, options.memorySection()));
            }
        }

        return builder.build();
    }
}
