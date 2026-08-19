package com.novacode.hook;

/**
 * 单条 hook 配置（第 12 章 spec F1）。reject 仅 pre_tool_use 有意义；async 仅非拦截事件允许。
 */
public record Hook(String id, HookEvent event, String condition, HookAction action,
                  boolean reject, boolean once, boolean async) {}
