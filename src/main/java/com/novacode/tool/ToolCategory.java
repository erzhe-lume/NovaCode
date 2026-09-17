package com.novacode.tool;

/** Permission classification: READ tools can run in parallel, WRITE/COMMAND must be serial.
 *  INTERNAL tools manage agent-private state (e.g. TodoWrite): always allowed, no HITL,
 *  and available in plan mode. */
public enum ToolCategory { READ, WRITE, COMMAND, INTERNAL }
