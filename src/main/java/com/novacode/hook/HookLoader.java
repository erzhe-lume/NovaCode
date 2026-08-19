package com.novacode.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 两级 hook 配置加载（第 12 章 spec F7）：用户级 + 项目级，缺失即空、非法跳过、集中校验。
 *
 * <ul>
 *   <li>user:    {@code <home>/.novacode/hooks.yaml}</li>
 *   <li>project: {@code <cwd>/.novacode/hooks.yaml}</li>
 * </ul>
 */
public class HookLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private HookLoader() {}

    /** 加载结果：已解析的 hooks + 集中校验/解析错误。 */
    public record LoadedHooks(List<Hook> hooks, List<String> errors) {}

    public static LoadedHooks load(Path projectRoot, Path userHome) {
        List<Hook> hooks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        collect(userHome.resolve(".novacode").resolve("hooks.yaml"), hooks, errors);
        collect(projectRoot.resolve(".novacode").resolve("hooks.yaml"), hooks, errors);
        errors.addAll(HookEngine.validate(hooks));
        return new LoadedHooks(hooks, errors);
    }

    private static void collect(Path file, List<Hook> hooks, List<String> errors) {
        if (!Files.exists(file)) return;
        try {
            HooksFile f = MAPPER.readValue(file.toFile(), HooksFile.class);
            if (f.getHooks() != null) {
                for (HookConfig hc : f.getHooks()) {
                    if (hc != null) hooks.add(hc.toHook());
                }
            }
        } catch (Exception e) {
            errors.add("跳过 " + file + ": " + e.getMessage());
        }
    }

    /** YAML 顶层容器：hooks 列表。 */
    public static class HooksFile {
        private List<HookConfig> hooks;
        public List<HookConfig> getHooks() { return hooks; }
        public void setHooks(List<HookConfig> hooks) { this.hooks = hooks; }
    }
}
