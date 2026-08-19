package com.novacode.permission;

/**
 * Thrown when the user cancels during a human-in-the-loop prompt (Esc /
 * Ctrl+C). The agent catches it in batched execution, fills the remaining calls
 * with cancel markers, and ends the turn cleanly (spec N4).
 */
public class PermissionCancelledException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public PermissionCancelledException() { super("Permission prompt cancelled by user"); }
}
