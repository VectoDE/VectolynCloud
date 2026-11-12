// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.launcher.security;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for the number parser of {@link ElevationDetector}, which reads a user id from system
 * output. It never throws.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class ElevationIdFuzzTest {

    @FuzzTest
    void parseIdNeverThrows(@NotNull String digits) {
        ElevationDetector.parseId(digits);
    }
}
