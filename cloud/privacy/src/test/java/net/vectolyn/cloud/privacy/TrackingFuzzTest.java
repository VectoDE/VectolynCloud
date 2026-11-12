// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.List;

/**
 * Fuzz target for {@link TrackingStore#decode}: any bytes either fail with
 * {@link TrackingStore.FormatException} or decode into records within the limits that encode and
 * decode to the same records again.
 */
class TrackingFuzzTest {

    @FuzzTest
    void decodeFailsOnlyWithFormatErrorsAndRoundTrips(byte @NotNull [] input) throws Exception {
        List<TrackedPlayer> players;
        try {
            players = TrackingStore.decode(input);
        } catch (TrackingStore.FormatException expected) {
            return;
        }
        assertTrue(players.size() <= TrackingStore.MAX_PLAYERS);
        assertEquals(players, TrackingStore.decode(TrackingStore.encode(players)));
    }
}
