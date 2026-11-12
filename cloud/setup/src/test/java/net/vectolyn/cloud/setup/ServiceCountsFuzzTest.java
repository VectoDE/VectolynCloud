// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for {@link ServiceCounts#parse(String)}, which reads the {@code static/min/max} answer
 * of the setup. It never throws; it returns null or counts that are not negative.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class ServiceCountsFuzzTest {

    @FuzzTest
    void parseNeverThrows(@NotNull String text) {
        ServiceCounts counts = ServiceCounts.parse(text);
        if (counts != null) {
            assertTrue(counts.staticServices() >= 0 && counts.minServices() >= 0 && counts.maxServices() >= 0);
        }
    }
}
