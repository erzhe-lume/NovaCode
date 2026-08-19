package com.novacode.tool.impl;

/** Computes a simple line-based diff for EditFile result display. */
public final class DiffUtil {

    private DiffUtil() {}

    public static String buildDiff(String before, String after) {
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);

        // Find common prefix
        int start = 0;
        while (start < oldLines.length && start < newLines.length
                && oldLines[start].equals(newLines[start])) start++;

        // Find common suffix
        int oldEnd = oldLines.length - 1, newEnd = newLines.length - 1;
        while (oldEnd >= start && newEnd >= start
                && oldLines[oldEnd].equals(newLines[newEnd])) { oldEnd--; newEnd--; }

        int additions = Math.max(0, newEnd - start + 1);
        int removals = Math.max(0, oldEnd - start + 1);

        var sb = new StringBuilder();
        sb.append(removals).append(" removal").append(removals == 1 ? "" : "s");
        sb.append(", ").append(additions).append(" addition").append(additions == 1 ? "" : "s").append("\n");

        for (int i = start; i <= oldEnd; i++)
            sb.append("- ").append(oldLines[i]).append("\n");
        for (int i = start; i <= newEnd; i++)
            sb.append("+ ").append(newLines[i]).append("\n");
        return sb.toString();
    }
}
