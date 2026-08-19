package com.novacode.hook;

/** 单条 hook 动作的执行结果。 */
public record HookResult(String hookId, String output, boolean success, boolean reject) {}
