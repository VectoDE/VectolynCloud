// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

    @Test
    void equalTextsHaveNoDiff() {
        assertEquals("", UnifiedDiff.diff("cloud.yml", "a\nb\n", "a\nb\n"));
    }

    @Test
    void oneChangedLineWithContext() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n";
        String after = "1\n2\n3\n4\nfive\n6\n7\n8\n";
        assertEquals("""
                --- a/cloud.yml
                +++ b/cloud.yml
                @@ -2,7 +2,7 @@
                 2
                 3
                 4
                -5
                +five
                 6
                 7
                 8
                """, UnifiedDiff.diff("cloud.yml", before, after));
    }

    @Test
    void newAndDeletedFiles() {
        assertEquals("""
                --- /dev/null
                +++ b/tasks/lobby.yml
                @@ -0,0 +1,2 @@
                +a
                +b
                """, UnifiedDiff.diff("tasks/lobby.yml", "", "a\nb\n"));
        assertEquals("""
                --- a/tasks/lobby.yml
                +++ /dev/null
                @@ -1 +0,0 @@
                -a
                """, UnifiedDiff.diff("tasks/lobby.yml", "a\n", ""));
    }

    @Test
    void distantChangesGetTwoHunksCloseOnesOne() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            lines.add(Integer.toString(i));
        }
        String before = String.join("\n", lines) + "\n";
        String twoHunks = before.replace("\n3\n", "\nthree\n").replace("\n27\n", "\ntwenty-seven\n");
        String diff = UnifiedDiff.diff("f", before, twoHunks);
        assertTrue(diff.contains("@@ -1,6 +1,6 @@"), diff);
        assertTrue(diff.contains("@@ -24,7 +24,7 @@"), diff);
        assertEquals(twoHunks, apply(before, diff));
        String oneHunk = before.replace("\n3\n", "\nthree\n").replace("\n8\n", "\neight\n");
        String merged = UnifiedDiff.diff("f", before, oneHunk);
        assertTrue(merged.contains("@@ -1,11 +1,11 @@"), merged);
        assertEquals(oneHunk, apply(before, merged));
    }

    @Test
    void appliedDiffGivesTheNewText() {
        String before = "a\nb\nc\nd\ne\nf\n";
        String after = "a\nx\nc\ny\nz\nf\ng\n";
        assertEquals(after, apply(before, UnifiedDiff.diff("f", before, after)));
    }

    @Test
    void largeDifferentFilesFallBackToABlockReplacement() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            a.append("old ").append(i).append('\n');
            b.append("new ").append(i).append('\n');
        }
        String diff = UnifiedDiff.diff("big", a.toString(), b.toString());
        assertEquals(b.toString(), apply(a.toString(), diff));
    }

    /** A tiny patcher: enough to check that hunks and line numbers are consistent. */
    private static String apply(String before, String diff) {
        List<String> old = before.isEmpty() ? List.of() : Arrays.asList(before.substring(0, before.length() - 1).split("\n", -1));
        List<String> out = new ArrayList<>();
        int position = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                continue;
            }
            if (line.startsWith("@@")) {
                String range = line.split(" ")[1].substring(1);
                int start = Integer.parseInt(range.split(",")[0]);
                int count = range.contains(",") ? Integer.parseInt(range.split(",")[1]) : 1;
                int target = count == 0 ? start : start - 1;
                while (position < target) {
                    out.add(old.get(position++));
                }
                continue;
            }
            char kind = line.charAt(0);
            if (kind == ' ') {
                assertEquals(old.get(position), line.substring(1));
                out.add(old.get(position++));
            } else if (kind == '-') {
                assertEquals(old.get(position), line.substring(1));
                position++;
            } else {
                out.add(line.substring(1));
            }
        }
        while (position < old.size()) {
            out.add(old.get(position++));
        }
        return out.isEmpty() ? "" : String.join("\n", out) + "\n";
    }
}
