package com.novacode.permission;

/**
 * Everything the UI needs to render a human-in-the-loop confirmation block
 * (spec F8) and to build the exact permanent allow rule on ALLOW_FOREVER.
 *
 * @param toolName    display name of the tool (e.g. "Bash")
 * @param argsPreview short preview of key arguments, e.g. "command=git push"
 * @param reason      why the call needs confirmation (mode + tool class)
 * @param ruleName    friendly name for the permanent rule (Bash/Read/Write/...)
 * @param ruleSubject exact command string or project-relative path for the rule
 */
public record AskContext(String toolName, String argsPreview, String reason,
                         String ruleName, String ruleSubject) {}
