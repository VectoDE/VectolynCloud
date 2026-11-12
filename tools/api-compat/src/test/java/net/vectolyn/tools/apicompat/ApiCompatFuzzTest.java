// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.apicompat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz targets for the version rule (the baseline is a git tag name) and the argument parser. Both
 * may only fail with an {@link IllegalArgumentException}. Regression mode in a normal test run.
 */
class ApiCompatFuzzTest {

    @FuzzTest
    void versionRuleFailsOnlyWithIllegalArgument(@NotNull String baseline, @NotNull String next) {
        try {
            VersionRule.breakingChangeAllowed(baseline, next);
            VersionRule.nextBreakingVersion(baseline);
        } catch (IllegalArgumentException expected) {
            // not [v]MAJOR.MINOR.PATCH[-suffix]
        }
    }

    @FuzzTest
    void argumentsFailOnlyWithIllegalArgument(@NotNull String @NotNull [] args) {
        try {
            ApiCompat.parse(args);
        } catch (IllegalArgumentException expected) {
            // unknown or missing argument, or a bad version
        }
    }
}
