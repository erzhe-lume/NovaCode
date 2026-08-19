package com.novacode.permission;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Built-in high-risk command blacklist (spec F1). Applies to command-execution
 * tools only; matched commands are denied outright and cannot be overridden by
 * any rule, mode (including bypassPermissions), or config. Heuristic defense —
 * not exhaustive by design, and not user-extensible.
 */
public class Blacklist {

    private static final Pattern RM = Pattern.compile("\\brm\\b");
    private static final Pattern R_FLAG = Pattern.compile("(?:^|[^a-z])(?:-[a-z]*r[a-z]*|--recursive)", Pattern.CASE_INSENSITIVE);
    private static final Pattern F_FLAG = Pattern.compile("(?:^|[^a-z])(?:-[a-z]*f[a-z]*|--force)", Pattern.CASE_INSENSITIVE);
    /** Root / home / user-dir targets. Bare ~ or a root path like /, /home, /root, /usr. */
    private static final Pattern ROOT_HOME_TARGET = Pattern.compile(
            "(?i)(?:[ \\t]/[ \\t]|[ \\t]/\\*|[ \\t]/$|~[ \\t]|~$|/home(?=[/\\s]|$)|/root(?=[/\\s]|$)|/usr(?=[/\\s]|$))");

    private record Entry(String label, Predicate<String> test) {}

    private static Entry regex(String label, String regex) {
        Pattern p = Pattern.compile(regex);
        return new Entry(label, cmd -> p.matcher(cmd).find());
    }

    private static final List<Entry> RULES = List.of(
        // rm -rf / , rm -rf ~ , rm -rf /home|/root|/usr (recursive delete of a root-level location)
        new Entry("递归强删根/家目录", cmd ->
                RM.matcher(cmd).find() && R_FLAG.matcher(cmd).find()
                        && F_FLAG.matcher(cmd).find() && ROOT_HOME_TARGET.matcher(cmd).find()),
        // dd of=/dev/sda etc — write to a raw block device
        regex("写块设备",
            "(?i)\\bdd\\b[^\\n;&|]{0,120}\\bof\\s*=\\s*(?:/dev/(?:[sh]d[a-z]\\b|vd[a-z]\\b|disk\\b)|\\\\\\\\.\\\\PhysicalDrive\\b)"),
        // mkfs / mkfs.ext4 / mkfs.xfs — format a filesystem
        regex("格式化文件系统", "(?i)(?:^|[\\s;&|])mkfs(?:\\.[a-z0-9_]+)?\\b"),
        // command > /dev/sda — redirect overwriting a disk device
        regex("重定向覆盖磁盘设备", "(?i)[\\s;|&][12]?>\\s*/dev/(?:[sh]d[a-z]|vd[a-z])\\b"),
        // classic fork bomb
        regex("fork 炸弹", ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;?\\s*:"),
        // Windows: rd /s /q C:\  or  C:\*  (whole-drive recursive delete)
        regex("Windows 整盘删除",
            "(?i)\\b(?:rd|rmdir)\\b[^\\n;&|]{0,60}/[a-z]*s[a-z]*[^\\n;&|]{0,40}/[a-z]*q[a-z]*[^\\n;&|]{0,40}[a-zA-Z]:\\\\(?:[ \\t]|\\*|$)"),
        // Windows: format C:  （冒号后不要求词边界，`format C:` / `format C:\x` 都拦截）
        regex("Windows 格式化", "(?i)\\bformat\\s+[a-zA-Z]:")
    );

    /** @return the matched danger label, or null if the command is not blacklisted */
    public String matches(String command) {
        for (Entry e : RULES) {
            if (e.test().test(command)) return e.label();
        }
        return null;
    }
}
