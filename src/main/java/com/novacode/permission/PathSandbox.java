package com.novacode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-root sandbox for file tools (spec F2/N2). Resolves symlinks first,
 * then does a prefix check — a symlink pointing outside the project is denied.
 * Targets that do not yet exist (new files, not-yet-created intermediate
 * directories) are resolved through their nearest existing ancestor, so they
 * are not wrongly denied. Reads and writes are treated alike. Bash does not go
 * through the sandbox (its danger is covered by blacklist + rules + mode).
 */
public class PathSandbox {

    private final Path root;
    private final Path rootReal;

    public PathSandbox(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.rootReal = real(root);
    }

    public Path root() { return root; }

    /** Resolve a tool-supplied path (absolute, or relative to the project root). */
    public Path resolve(Path target) {
        Path abs = target.isAbsolute() ? target.normalize() : root.resolve(target).normalize();
        return abs;
    }

    /**
     * @return a violation message, or null when the target is inside the project
     */
    public String check(Path target) {
        Path abs = resolve(target);
        Path resolved = resolveSymlinks(abs);
        if (!resolved.startsWith(rootReal)) {
            return "路径超出项目根目录: " + abs + " (沙箱: " + root + ")";
        }
        return null;
    }

    /** Resolve symlinks via the nearest existing ancestor (spec N2). A broken
     *  symlink (target doesn't exist) is treated as a symlink, not a missing
     *  segment — otherwise a broken link pointing outside the project would be
     *  re-attached to the root and wrongly pass the prefix check. */
    private static Path resolveSymlinks(Path p) {
        Path cur = p;
        List<String> tail = new ArrayList<>();
        while (cur != null && !Files.exists(cur) && !Files.isSymbolicLink(cur)) {
            Path name = cur.getFileName();
            if (name != null) tail.add(0, name.toString());
            cur = cur.getParent();
        }
        Path base = cur != null ? cur : p;
        Path realBase = real(base);
        Path result = realBase;
        for (String seg : tail) result = result.resolve(seg);
        return result;
    }

    /** Resolve a path to canonical form, following symlinks — including broken
     *  ones, which {@link Path#toRealPath()} refuses. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            // Leaf may be a broken symlink: resolve its literal target manually.
            try {
                if (Files.isSymbolicLink(p)) {
                    Path target = Files.readSymbolicLink(p);
                    Path parent = p.getParent();
                    if (parent == null) parent = p.toAbsolutePath().getParent();
                    return (target.isAbsolute() ? target
                            : (parent != null ? parent.resolve(target) : target))
                            .normalize();
                }
            } catch (IOException ignored) {}
            return p;
        }
    }

}
