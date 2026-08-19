package com.novacode.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * One permission config file's YAML shape (spec F4):
 *
 * <pre>
 * mode: default
 * allow:
 *   - "Bash(git *)"
 * deny:
 *   - "Bash(git push)"
 * </pre>
 */
public class PermissionFile {

    private String mode;
    private List<String> allow = new ArrayList<>();
    private List<String> deny = new ArrayList<>();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public List<String> getAllow() { return allow; }
    public void setAllow(List<String> allow) { this.allow = allow != null ? allow : new ArrayList<>(); }

    public List<String> getDeny() { return deny; }
    public void setDeny(List<String> deny) { this.deny = deny != null ? deny : new ArrayList<>(); }
}
