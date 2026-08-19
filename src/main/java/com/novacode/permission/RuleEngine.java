package com.novacode.permission;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Three-tier rule engine (spec F4). Tiers are checked LOCAL → PROJECT → USER;
 * the first tier with any match wins. Within a tier deny beats allow.
 */
public class RuleEngine {

    private final Map<RuleTier, List<Rule>> tiers = new EnumMap<>(RuleTier.class);

    public void addRule(RuleTier tier, Rule rule) {
        tiers.computeIfAbsent(tier, k -> new ArrayList<>()).add(rule);
    }

    /**
     * @return ALLOW / DENY when a rule matched, or null when nothing matched
     */
    public Decision match(String friendlyName, String subject) {
        for (RuleTier tier : RuleTier.values()) {
            List<Rule> list = tiers.get(tier);
            if (list == null || list.isEmpty()) continue;
            boolean anyAllow = false;
            for (Rule r : list) {
                if (!r.friendlyName().equals(friendlyName)) continue;
                if (!r.pattern().matcher(subject).matches()) continue;
                if (!r.allow()) return Decision.deny("规则(" + tier + ") 拒绝: " + r.spec());
                anyAllow = true;
            }
            if (anyAllow) return Decision.allow();
        }
        return null;
    }

    public boolean isEmpty() {
        return tiers.values().stream().allMatch(List::isEmpty);
    }

    /** 展平三层规则（带 source 标明来源 tier，第 10 章 /permission 用）。 */
    public List<Rule> allRules() {
        var all = new ArrayList<Rule>();
        for (RuleTier tier : RuleTier.values()) {
            List<Rule> list = tiers.get(tier);
            if (list != null) all.addAll(list);
        }
        return all;
    }
}
