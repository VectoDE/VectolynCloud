// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.List;

/**
 * Fuzz target for {@link StatisticsCollector#decode}: any bytes either fail with
 * {@link StatisticsCollector.FormatException} or decode into a list within the limits that encodes
 * and decodes to the same list again.
 */
class StatisticsFuzzTest {

    @FuzzTest
    void decodeFailsOnlyWithFormatErrorsAndRoundTrips(byte @NotNull [] input) throws Exception {
        List<ModeStatistics> modes;
        try {
            modes = StatisticsCollector.decode(input);
        } catch (StatisticsCollector.FormatException expected) {
            return;
        }
        assertTrue(modes.size() <= StatisticsCollector.MAX_MODES);
        assertEquals(modes, StatisticsCollector.decode(StatisticsCollector.encode(modes)));
    }
}
