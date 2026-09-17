package com.novacode.permission;

import com.novacode.tool.Tool;
import com.novacode.tool.ToolRegistry;
import com.novacode.tool.impl.TodoTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 注册表 schema 过滤 + 权限引擎分层判定（INTERNAL 直通 / 未知工具拒绝 / 默认模式写拒绝）。 */
class RegistryPermissionTest {

    @TempDir
    Path dir;

    @Test
    void planModeIncludesInternalExcludesWrite() {
        var reg = ToolRegistry.createDefault();
        var planSchemas = reg.getReadOnlySchemas("anthropic");
        assertTrue(planSchemas.stream().anyMatch(s -> "TodoWrite".equals(s.get("name"))),
                "plan 模式应包含 INTERNAL 工具");
        assertTrue(planSchemas.stream().noneMatch(s -> "WriteFile".equals(s.get("name"))),
                "plan 模式不应包含写工具");
        assertTrue(planSchemas.stream().noneMatch(s -> "Bash".equals(s.get("name"))),
                "plan 模式不应包含命令工具");
    }

    @Test
    void openAiSchemaConversionWrapsInFunction() {
        var reg = ToolRegistry.createDefault();
        var schemas = reg.getAllSchemas("openai");
        var todo = schemas.stream()
                .filter(s -> s.get("function") instanceof Map<?, ?> f && "TodoWrite".equals(f.get("name")))
                .findFirst().orElseThrow();
        assertEquals("function", todo.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) todo.get("function");
        assertNotNull(fn.get("parameters"), "function 应带 parameters");
    }

    @Test
    void internalToolAlwaysAllowedWithoutPrompter() {
        var engine = new PermissionEngine(new Blacklist(),
                new PathSandbox(dir), new RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
        var todo = new TodoTool(dir.resolve("todos.json"));
        Decision d = engine.decide(todo, Map.of("todos", List.of()));
        assertEquals(Decision.Verdict.ALLOW, d.verdict(), "INTERNAL 应免确认直通");
    }

    @Test
    void nullToolDenied() {
        var engine = new PermissionEngine(new Blacklist(),
                new PathSandbox(dir), new RuleEngine(), () -> PermissionMode.BYPASS_PERMISSIONS, null, null);
        assertEquals(Decision.Verdict.DENY, engine.decide((Tool) null, Map.of()).verdict());
    }

    @Test
    void writeFileInDefaultModeWithNullPrompterDenied() {
        var engine = new PermissionEngine(new Blacklist(),
                new PathSandbox(dir), new RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
        var reg = ToolRegistry.createDefault();
        Tool write = reg.get("WriteFile");
        Decision d = engine.decide(write, Map.of("file_path", dir.resolve("x.txt").toString(), "content", "hi"));
        assertEquals(Decision.Verdict.DENY, d.verdict(),
                "DEFAULT 模式写工具落 Ask，无确认通道应降级 Deny（N7）");
    }

    @Test
    void readAllowedInDefaultMode() {
        var engine = new PermissionEngine(new Blacklist(),
                new PathSandbox(dir), new RuleEngine(), () -> PermissionMode.DEFAULT, null, null);
        var reg = ToolRegistry.createDefault();
        Decision d = engine.decide(reg.get("ReadFile"),
                Map.of("file_path", dir.resolve("a.txt").toString()));
        assertEquals(Decision.Verdict.ALLOW, d.verdict());
    }

    @Test
    void pathOutsideSandboxDenied() {
        var engine = new PermissionEngine(new Blacklist(),
                new PathSandbox(dir), new RuleEngine(),
                () -> PermissionMode.ACCEPT_EDITS, null, null);
        var reg = ToolRegistry.createDefault();
        Path outside = dir.getParent() != null
                ? dir.resolveSibling(dir.getFileName() + "-outside.txt")
                : Path.of("C:", "Windows", "Temp", "novacode-outside.txt");
        Decision d = engine.decide(reg.get("WriteFile"),
                Map.of("file_path", outside.toString(), "content", "hi"));
        assertEquals(Decision.Verdict.DENY, d.verdict(), "沙箱外写应拒绝");
    }
}
