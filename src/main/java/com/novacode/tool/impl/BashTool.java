package com.novacode.tool.impl;

import com.novacode.tool.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BashTool implements Tool {

    private static final int MAX_TIMEOUT = 600;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024; // 1 MB output cap
    private Path cwd = Path.of(System.getProperty("user.dir"));

    public BashTool() {}
    public void setCwd(Path cwd) { if (cwd != null) this.cwd = cwd; }

    @Override public String name() { return "Bash"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }
    @Override public String description() {
        return "Execute a shell command. Use ONLY when no dedicated tool applies. "
                + "Do NOT use Bash when a dedicated tool is available: use ReadFile instead of cat/head/tail/sed, "
                + "EditFile instead of sed/awk, WriteFile instead of echo/cat heredoc, Glob instead of find/ls, "
                + "Grep instead of grep/rg. Reserve Bash for system commands and operations that require shell execution.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description", "Shell command to execute"),
                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
            ),
            "required", List.of("command")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = ReadFileTool.str(args, "command", "");
        if (command.isEmpty()) return ToolResult.error("Error: command is required");

        int timeout = ReadFileTool.integer(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) timeout = MAX_TIMEOUT;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                isWindows() ? "cmd.exe" : "bash",
                isWindows() ? "/c" : "-c",
                command
            );
            pb.redirectErrorStream(true);
            pb.directory(cwd.toFile());

            Process process = pb.start();

            // Drain merged stdout/stderr on a separate thread so a chatty
            // command can never block on a full OS pipe buffer.
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            AtomicBoolean truncated = new AtomicBoolean(false);
            Thread drainer = Thread.startVirtualThread(() -> {
                try (InputStream stream = process.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = stream.read(chunk)) != -1) {
                        synchronized (buf) {
                            if (buf.size() + n > MAX_OUTPUT_BYTES) {
                                int room = MAX_OUTPUT_BYTES - buf.size();
                                if (room > 0) buf.write(chunk, 0, room);
                                truncated.set(true);
                            } else {
                                buf.write(chunk, 0, n);
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process closed the stream; nothing to drain
                }
            });

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                drainer.join(1000);
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }
            // Process finished normally; let the drainer flush any trailing bytes.
            drainer.join(1000);

            String output;
            synchronized (buf) {
                output = buf.toString();
            }

            int exitCode = process.exitValue();
            var sb = new StringBuilder();
            if (!output.isEmpty()) {
                sb.append(output);
                if (!output.endsWith("\n")) sb.append('\n');
            }
            if (truncated.get()) {
                sb.append("... [output truncated at ").append(MAX_OUTPUT_BYTES).append(" bytes]\n");
            }
            if (exitCode != 0) {
                sb.append("Exit code ").append(exitCode).append('\n');
            }
            return new ToolResult(sb.toString(), exitCode != 0);

        } catch (IOException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
