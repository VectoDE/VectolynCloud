// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz targets for the value parsers an operator types into the configuration: {@link JavaSelection},
 * {@link MemorySize}, {@link TimeSpan} and {@link TimeOfDay}. Each one may only fail with an
 * {@link IllegalArgumentException}.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class ModelParsersFuzzTest {

    @FuzzTest
    void javaSelectionFailsOnlyWithIllegalArgument(@NotNull String input) {
        try {
            JavaSelection.parse(input);
        } catch (IllegalArgumentException expected) {
            // a rejected value is the normal answer to bad input
        }
    }

    @FuzzTest
    void memorySizeFailsOnlyWithIllegalArgument(@NotNull String input) {
        try {
            assertTrue(MemorySize.parse(input).megabytes() >= 1);
        } catch (IllegalArgumentException expected) {
            // a rejected value is the normal answer to bad input
        }
    }

    @FuzzTest
    void timeSpanFailsOnlyWithIllegalArgument(@NotNull String input) {
        try {
            assertTrue(!TimeSpan.parse(input).duration().isNegative());
        } catch (IllegalArgumentException expected) {
            // a rejected value is the normal answer to bad input
        }
    }

    @FuzzTest
    void timeOfDayFailsOnlyWithIllegalArgument(@NotNull String input) {
        try {
            TimeOfDay time = TimeOfDay.parse(input);
            assertTrue(time.toString().equals(input));
        } catch (IllegalArgumentException expected) {
            // a rejected value is the normal answer to bad input
        }
    }
}
