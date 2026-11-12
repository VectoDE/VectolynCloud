// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.monitor.metric;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz targets for the parsers of system tool output and kernel files ({@code ps}, {@code tasklist},
 * {@code /proc/<pid>/status}, {@code /proc/meminfo}). The text depends on the operating system and
 * its locale, so none of them may throw, and no value they return is negative.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class ProcessOutputFuzzTest {

    @FuzzTest
    void parsePsNeverThrows(@NotNull String output) {
        ResidentMemoryProbe.parsePs(output).values().forEach(rss -> assertTrue(rss >= 0));
    }

    @FuzzTest
    void parseTasklistCsvNeverThrows(@NotNull String output) {
        ResidentMemoryProbe.parseTasklistCsv(output).values().forEach(rss -> assertTrue(rss >= 0));
    }

    @FuzzTest
    void parseProcStatusNeverThrows(@NotNull String status) {
        ResidentMemoryProbe.parseProcStatus(status).ifPresent(rss -> assertTrue(rss >= 0));
    }

    @FuzzTest
    void parseMeminfoNeverThrows(@NotNull String text, long mxTotal) {
        SystemMetricSource.parseMeminfo(text, mxTotal).ifPresent(available -> assertTrue(available >= 0));
    }
}
