package com.novacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the three permission config tiers (spec F4) with graceful degradation
 * (N5): a missing file is an empty tier; a malformed file is skipped entirely
 * and never prevents engine construction.
 *
 * <ul>
 *   <li>user:   {@code <home>/.novacode/permissions.yaml}</li>
 *   <li>project:{@code <cwd>/.novacode/permissions.yaml}</li>
 *   <li>local:  {@code <cwd>/.novacode/permissions.local.yaml} (gitignored)</li>
 * </ul>
 */
public class RuleLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build());

    private RuleLoader() {}

    public static PermissionConfig load() {
        return load(Path.of(System.getProperty("user.dir")), Path.of(System.getProperty("user.home")));
    }

    public static PermissionConfig load(Path projectRoot, Path userHome) {
        var engine = new RuleEngine();

        PermissionFile userFile = read(userHome.resolve(".novacode").resolve("permissions.yaml"));
        PermissionFile projectFile = read(projectRoot.resolve(".novacode").resolve("permissions.yaml"));
        PermissionFile localFile = read(projectRoot.resolve(".novacode").resolve("permissions.local.yaml"));

        loadTier(engine, RuleTier.USER, userFile);
        loadTier(engine, RuleTier.PROJECT, projectFile);
        loadTier(engine, RuleTier.LOCAL, localFile);

        return new PermissionConfig(engine, firstMode(localFile, projectFile, userFile));
    }

    private static void loadTier(RuleEngine engine, RuleTier tier, PermissionFile f) {
        if (f == null) return;
        if (f.getAllow() != null)
            for (String s : f.getAllow()) engine.addRule(tier, Rule.of(s, true, tier.name()));
        if (f.getDeny() != null)
            for (String s : f.getDeny()) engine.addRule(tier, Rule.of(s, false, tier.name()));
    }

    private static PermissionMode firstMode(PermissionFile... files) {
        for (var f : files) {
            if (f != null && f.getMode() != null && !f.getMode().isBlank())
                return PermissionMode.parse(f.getMode());
        }
        return PermissionMode.DEFAULT;
    }

    /** Read one config file; missing or malformed → null (never throws). */
    public static PermissionFile read(Path p) {
        if (!Files.exists(p)) return null;
        try {
            return MAPPER.readValue(p.toFile(), PermissionFile.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Write one config file (creates parent dirs). Used by permanent-allow. */
    public static void write(Path p, PermissionFile f) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        MAPPER.writeValue(p.toFile(), f);
    }
}
