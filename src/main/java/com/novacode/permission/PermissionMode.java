package com.novacode.permission;

import com.novacode.tool.ToolCategory;

/**
 * Four permission modes (spec F5/F7). When the rule engine does not match a
 * call, the mode supplies the fallback verdict by tool category. The fallback
 * value set is strictly {Allow, Ask} — Deny can only come from blacklist,
 * sandbox, an explicit deny rule, or user rejection in HITL.
 */
public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS_PERMISSIONS;

    /** true = fallback Allow; false = fallback Ask (spec F5 matrix). */
    public boolean allows(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> category == ToolCategory.READ;
            case ACCEPT_EDITS -> category != ToolCategory.COMMAND;
            case PLAN -> category == ToolCategory.READ; // plan fallback is defensive Ask for writes/commands
            case BYPASS_PERMISSIONS -> true; // blacklist/sandbox still apply (handled earlier in pipeline)
        };
    }

    /** Shift+Tab cycling order (spec F7). */
    public PermissionMode next() {
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> PLAN;
            case PLAN -> BYPASS_PERMISSIONS;
            case BYPASS_PERMISSIONS -> DEFAULT;
        };
    }

    /** Parse a config label; unknown/missing → DEFAULT (never throws). */
    public static PermissionMode parse(String s) {
        if (s == null) return DEFAULT;
        return switch (s.trim().toLowerCase()) {
            case "acceptedits", "accept-edits" -> ACCEPT_EDITS;
            case "plan" -> PLAN;
            case "bypasspermissions", "bypass" -> BYPASS_PERMISSIONS;
            default -> DEFAULT;
        };
    }

    /** Status-bar label (spec F7: mode is shown where the provider name used to be). */
    public String label() {
        return switch (this) {
            case DEFAULT -> "default";
            case ACCEPT_EDITS -> "acceptEdits";
            case PLAN -> "plan";
            case BYPASS_PERMISSIONS -> "bypassPermissions";
        };
    }
}
