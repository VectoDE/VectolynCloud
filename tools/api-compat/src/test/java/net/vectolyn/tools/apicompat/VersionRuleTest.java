// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.apicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VersionRuleTest {

    @ParameterizedTest
    @CsvSource({
        // Below 1.0.0 the minor counts as the major.
        "v0.1.1-dev, 0.1.0-SNAPSHOT, false",
        "v0.1.1-dev, 0.1.2-SNAPSHOT, false",
        "v0.1.1-dev, 0.2.0-SNAPSHOT, true",
        "v0.1.1-dev, 1.0.0-SNAPSHOT, true",
        "v0.3.0-dev.2, 0.3.9, false",
        // From 1.0.0 only a new major.
        "v1.4.2, 1.5.0-SNAPSHOT, false",
        "v1.4.2, 1.4.3, false",
        "v1.4.0-lts, 2.0.0-SNAPSHOT, true",
        "v2.0.0-rc.1, 2.0.0, false",
        // A next version below the baseline is not a step at all.
        "v1.4.2, 0.9.0, false",
    })
    void breakingChangesNeedTheRightStep(String baseline, String next, boolean allowed) {
        assertEquals(allowed, VersionRule.breakingChangeAllowed(baseline, next));
    }

    @ParameterizedTest
    @CsvSource({"v0.1.1-dev, 0.2.0", "v0.9.3-dev, 0.10.0", "v1.4.2, 2.0.0"})
    void theMessageNamesTheSmallestBreakingVersion(String baseline, String expected) {
        assertEquals(expected, VersionRule.nextBreakingVersion(baseline));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.0", "v01.0.0", "1.0.0+build", "x1.0.0", "1.0.0-", "9999999999.0.0"})
    void malformedVersionsAreRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> VersionRule.breakingChangeAllowed(text, "1.0.0"));
    }
}
