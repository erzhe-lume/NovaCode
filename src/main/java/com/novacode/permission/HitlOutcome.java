package com.novacode.permission;

/**
 * User's decision in a human-in-the-loop prompt (spec F8).
 */
public enum HitlOutcome {
    /** 允许本次 — allow, no record. */
    ALLOW_ONCE,
    /** 永久 — allow and write an exact allow rule to the local config. */
    ALLOW_FOREVER,
    /** 拒绝本次 — deny (fed back so the model adjusts). */
    DENY,
    /** Esc / Ctrl+C — cancel the turn cleanly (spec N4). */
    CANCELLED
}
