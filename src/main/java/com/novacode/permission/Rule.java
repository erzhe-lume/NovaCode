package com.novacode.permission;

import java.util.regex.Pattern;

/**
 * A single permission rule declared as {@code Tool(pattern)} (spec F3).
 * {@code Bash} alone matches all calls of that tool. Exact match when the
 * pattern has no wildcard; glob otherwise. {@code **} crosses directory
 * segments only for file paths — for command strings it equals {@code *}.
 *
 * @param friendlyName tool friendly name (Bash/Read/Write/Edit/Glob/Grep)
 * @param pattern      compiled matcher against the call subject
 * @param allow        true = allow rule, false = deny rule
 * @param source       human-readable origin (config tier / permanent write)
 * @param spec         original rule text ({@code Tool(pattern)}) for readable deny reasons
 */
public record Rule(String friendlyName, Pattern pattern, boolean allow, String source, String spec) {

    /** Parse {@code "Tool(pattern)"} — split at the first '(' and last ')'. */
    public static Rule of(String spec, boolean allow, String source) {
        String name = spec.trim();
        String patternSpec = null;
        int open = spec.indexOf('(');
        if (open > 0) {
            int close = spec.lastIndexOf(')');
            if (close > open) {
                name = spec.substring(0, open).trim();
                patternSpec = spec.substring(open + 1, close);
            }
        }
        String trimmed = spec.trim();
        if (patternSpec == null) {
            // No pattern segment → matches every call of that tool (spec F3).
            return new Rule(name, Pattern.compile(".*"), allow, source, trimmed);
        }
        boolean pathSemantics = !"Bash".equals(name);
        return new Rule(name, globToRegex(patternSpec, pathSemantics), allow, source, trimmed);
    }

    /** Convert a glob to an anchored regex. Command strings: * matches anything. */
    public static Pattern globToRegex(String glob, boolean pathSemantics) {
        String g = glob;
        boolean dirToo = pathSemantics && g.endsWith("/**");
        if (dirToo) g = g.substring(0, g.length() - 3);
        var sb = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '\\' && i + 1 < g.length()) {
                sb.append(Pattern.quote(String.valueOf(g.charAt(i + 1))));
                i++;
                continue;
            }
            if (c == '*') {
                boolean doubleStar = i + 1 < g.length() && g.charAt(i + 1) == '*';
                if (doubleStar && pathSemantics && i + 2 < g.length() && g.charAt(i + 2) == '/') {
                    sb.append("(?:.*/)?"); // **/ → zero or more directories
                    i += 2;
                } else if (doubleStar) {
                    sb.append(".*");
                    i += 1;
                } else {
                    sb.append(pathSemantics ? "[^/]*" : ".*");
                }
            } else if (c == '?') {
                sb.append(pathSemantics ? "[^/]" : ".");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append(dirToo ? "(?:/.*)?$" : "$");
        return Pattern.compile(sb.toString());
    }
}
