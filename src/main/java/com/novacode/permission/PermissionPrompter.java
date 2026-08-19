package com.novacode.permission;

/**
 * Human-in-the-loop callback. Implemented by the UI layer; called from the
 * agent (virtual) thread when the pipeline lands on Ask. The implementation
 * renders a confirmation block and blocks until the user decides.
 */
@FunctionalInterface
public interface PermissionPrompter {

    HitlOutcome ask(AskContext ctx);
}
