// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.io;

import java.util.ArrayList;
import java.util.List;

/**
 * A line diff in the unified format ({@code diff -u}), for the preview of a configuration change.
 *
 * <p>Common lines at the start and the end are cut off first; the rest is compared with a longest
 * common subsequence. Above {@link #MAX_CELLS} comparisons (two very different large files) the
 * middle part is shown as removed and added as a whole, which is still a correct diff, only a
 * longer one.
 */
public final class UnifiedDiff {

    /** Lines of context around each change. */
    public static final int CONTEXT = 3;
    /** Upper limit of the comparison table, about 16 MB of memory. */
    static final long MAX_CELLS = 4_000_000L;

    private UnifiedDiff() {
    }

    /**
     * The diff from {@code before} to {@code after}.
     *
     * @param file   file name for the {@code ---} and {@code +++} lines
     * @param before old content; empty for a new file
     * @param after  new content; empty for a deleted file
     * @return the diff with LF line ends, empty if both are equal
     */
    public static String diff(String file, String before, String after) {
        if (before.equals(after)) {
            return "";
        }
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<Op> ops = compare(a, b);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before.isEmpty() ? "/dev/null" : "a/" + file).append('\n');
        out.append("+++ ").append(after.isEmpty() ? "/dev/null" : "b/" + file).append('\n');
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            // Extend the hunk while the next change is close enough to share context.
            while (true) {
                while (end < ops.size() && ops.get(end).kind != ' ') {
                    end++;
                }
                int next = end;
                while (next < ops.size() && ops.get(next).kind == ' ') {
                    next++;
                }
                if (next < ops.size() && next - end <= 2 * CONTEXT) {
                    end = next;
                } else {
                    end = Math.min(ops.size(), end + CONTEXT);
                    break;
                }
            }
            hunk(out, ops, start, end);
            i = end;
        }
        return out.toString();
    }

    private static void hunk(StringBuilder out, List<Op> ops, int start, int end) {
        int oldStart = ops.get(start).oldLine;
        int newStart = ops.get(start).newLine;
        int oldCount = 0;
        int newCount = 0;
        for (int k = start; k < end; k++) {
            char kind = ops.get(k).kind;
            if (kind != '+') {
                oldCount++;
            }
            if (kind != '-') {
                newCount++;
            }
        }
        out.append("@@ -").append(range(oldStart, oldCount)).append(" +").append(range(newStart, newCount))
                .append(" @@\n");
        for (int k = start; k < end; k++) {
            out.append(ops.get(k).kind).append(ops.get(k).text).append('\n');
        }
    }

    private static String range(int start, int count) {
        // diff -u counts lines from 1; an empty range names the line before it.
        int first = count == 0 ? start : start + 1;
        return count == 1 ? Integer.toString(first) : first + "," + count;
    }

    /** One line of the edit script with its position before it in the old and new file. */
    private record Op(char kind, String text, int oldLine, int newLine) {
    }

    private static List<Op> compare(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>();
        int oldLine = 0;
        int newLine = 0;
        for (int k = 0; k < prefix; k++) {
            ops.add(new Op(' ', a.get(k), oldLine++, newLine++));
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);
        long cells = (long) (midA.size() + 1) * (midB.size() + 1);
        if (cells > MAX_CELLS) {
            for (String line : midA) {
                ops.add(new Op('-', line, oldLine++, newLine));
            }
            for (String line : midB) {
                ops.add(new Op('+', line, oldLine, newLine++));
            }
        } else {
            int n = midA.size();
            int m = midB.size();
            int[][] lcs = new int[n + 1][m + 1];
            for (int x = n - 1; x >= 0; x--) {
                for (int y = m - 1; y >= 0; y--) {
                    lcs[x][y] = midA.get(x).equals(midB.get(y))
                            ? lcs[x + 1][y + 1] + 1 : Math.max(lcs[x + 1][y], lcs[x][y + 1]);
                }
            }
            int x = 0;
            int y = 0;
            while (x < n || y < m) {
                if (x < n && y < m && midA.get(x).equals(midB.get(y))) {
                    ops.add(new Op(' ', midA.get(x++), oldLine++, newLine++));
                    y++;
                } else if (x < n && (y == m || lcs[x + 1][y] >= lcs[x][y + 1])) {
                    ops.add(new Op('-', midA.get(x++), oldLine++, newLine));
                } else {
                    ops.add(new Op('+', midB.get(y++), oldLine, newLine++));
                }
            }
        }
        for (int k = a.size() - suffix; k < a.size(); k++) {
            ops.add(new Op(' ', a.get(k), oldLine++, newLine++));
        }
        return ops;
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        String normalized = text.replace("\r\n", "\n");
        int from = 0;
        while (from < normalized.length()) {
            int newline = normalized.indexOf('\n', from);
            if (newline < 0) {
                lines.add(normalized.substring(from));
                break;
            }
            lines.add(normalized.substring(from, newline));
            from = newline + 1;
        }
        return lines;
    }
}
