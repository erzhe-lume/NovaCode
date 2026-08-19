package com.novacode.permission;

/**
 * Final verdict of the permission pipeline. Ask is resolved inside the pipeline
 * (by HITL) and never escapes — the orchestrator only ever sees Allow or Deny.
 *
 * @param verdict  ALLOW or DENY
 * @param reason   human-readable reason; empty for ALLOW, carries the source
 *                 tag (黑名单/沙箱/规则/用户) for DENY (spec F9)
 */
public record Decision(Verdict verdict, String reason) {

    public enum Verdict { ALLOW, DENY }

    public static Decision allow() { return new Decision(Verdict.ALLOW, ""); }

    public static Decision deny(String reason) { return new Decision(Verdict.DENY, reason); }
}
