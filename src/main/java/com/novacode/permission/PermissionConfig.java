package com.novacode.permission;

/**
 * Result of loading the three permission config tiers.
 *
 * @param engine      rule engine populated from all loadable tiers
 * @param defaultMode startup mode: LOCAL > PROJECT > USER, else DEFAULT (spec F4/F5)
 */
public record PermissionConfig(RuleEngine engine, PermissionMode defaultMode) {}
