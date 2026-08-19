package com.novacode.permission;

/**
 * Configuration tiers for rules. Enum order is priority order: LOCAL beats
 * PROJECT beats USER (spec F4: 本地 > 项目 > 用户).
 */
public enum RuleTier { LOCAL, PROJECT, USER }
