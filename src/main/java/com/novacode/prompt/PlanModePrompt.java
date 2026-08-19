package com.novacode.prompt;

/**
 * Generates plan-mode reminders injected into the conversation to enforce the
 * read-only planning workflow.
 *
 * <p>Reminders are injected via the {@code <system-reminder>} mechanism (F6),
 * one per Agent iteration, with frequency control (F7): the first iteration
 * gets the full reminder, every {@link #REMINDER_INTERVAL} iterations repeats
 * the full version, everything else gets a one-line sparse reminder.
 */
public final class PlanModePrompt {

    private static final int REMINDER_INTERVAL = 5;

    private static final String PLAN_MODE_FULL_REMINDER = """
            Plan mode is active. The user indicated that they do not want you to execute yet -- you \
            MUST NOT make any edits (with the exception of the plan file mentioned below), run any \
            non-readonly tools, or otherwise make any changes to the system. This supersedes any other \
            instructions you have received.

            ## Plan File Info:
            %s

            You should build your plan incrementally by writing to or editing this file. NOTE that this \
            is the only file you are allowed to edit -- other than this you are only allowed to take \
            READ-ONLY actions (ReadFile, Glob, Grep).

            ## Plan Workflow

            ### Phase 1: Initial Understanding
            Goal: Gain a comprehensive understanding of the user's request by reading through code and \
            asking them questions. Read the relevant files with ReadFile, search with Glob and Grep, \
            and reuse existing functions and patterns where possible.

            ### Phase 2: Design
            Goal: Design an implementation approach based on the user's intent and your exploration \
            results from Phase 1. Consider the main alternatives and their tradeoffs.

            ### Phase 3: Review
            Goal: Review your plan and ensure it aligns with the user's original request. Ask the user \
            to clarify any remaining questions.

            ### Phase 4: Final Plan
            Goal: Write your final plan to the plan file (the only file you can edit).
            - Begin with a Context section: explain why this change is being made.
            - Include only your recommended approach, not all alternatives.
            - Include the paths of critical files to be modified.
            - Include a verification section describing how to test the changes end-to-end.

            ### Phase 5: Present the plan
            At the end of your turn, present the final plan to the user and wait for approval. Do not \
            start implementing until the user approves with /do.""";

    private static final String PLAN_MODE_SPARSE_REMINDER =
            "Plan mode still active (see full instructions earlier in conversation). "
                    + "Read-only except plan file (%s). Follow the 5-phase workflow. "
                    + "Present the plan and wait for /do approval before implementing.";

    private static final String PLAN_MODE_REENTRY_REMINDER = """
            ## Re-entering Plan Mode

            You are returning to plan mode after having previously exited it. A plan file exists at %s \
            from your previous planning session.

            **Before proceeding with any new planning, you should:**
            1. Read the existing plan file to understand what was previously planned
            2. Evaluate the user's current request against that plan
            3. Decide how to proceed:
               - Different task: start fresh by overwriting the existing plan
               - Same task, continuing: modify the existing plan while cleaning up outdated sections
            4. Always edit the plan file one way or the other before presenting the final plan

            Treat this as a fresh planning session. Do not assume the existing plan is relevant without \
            evaluating it first.""";

    private static final String PLAN_MODE_EXIT_REMINDER =
            "## Exited Plan Mode\n\n"
                    + "You have exited plan mode. You can now make edits, run tools, and take actions.%s";

    private PlanModePrompt() {}

    /**
     * Build the plan-mode reminder injected at each assistant turn.
     *
     * @param planPath   path to the plan file
     * @param planExists whether the plan file already exists on disk
     * @param iteration  1-based turn counter within the plan-mode session
     * @return the reminder string to inject
     */
    public static String buildReminder(String planPath, boolean planExists, int iteration) {
        String planFileInfo = "Plan file: " + planPath;
        if (planExists) {
            planFileInfo += "\nA plan file already exists at " + planPath
                    + ". You can read it and make incremental edits.";
        } else {
            planFileInfo += "\nNo plan file exists yet. You should create your plan at " + planPath
                    + " when ready.";
        }

        if (iteration == 1) {
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }

        if ((iteration - 1) % REMINDER_INTERVAL == 0) {
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }

        return String.format(PLAN_MODE_SPARSE_REMINDER, planPath);
    }

    /** Reminder shown when re-entering plan mode after previously exiting. */
    public static String buildReentryReminder(String planPath) {
        return String.format(PLAN_MODE_REENTRY_REMINDER, planPath);
    }

    /** Reminder shown immediately after exiting plan mode. */
    public static String buildExitReminder(String planPath, boolean planExists) {
        String extra = "";
        if (planExists) {
            extra = " The plan file is located at " + planPath + " if you need to reference it.";
        }
        return String.format(PLAN_MODE_EXIT_REMINDER, extra);
    }
}
