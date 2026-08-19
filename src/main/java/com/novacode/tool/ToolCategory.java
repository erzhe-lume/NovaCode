package com.novacode.tool;

/** Permission classification: READ tools can run in parallel, WRITE/COMMAND must be serial. */
public enum ToolCategory { READ, WRITE, COMMAND }
