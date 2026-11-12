// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.List;

/**
 * Fuzz target for {@link IpBanStore#decode}: any bytes either fail with
 * {@link IpBanStore.FormatException} or decode into a list within the limits that encodes and
 * decodes to the same list again.
 */
class IpBanStoreFuzzTest {

    @FuzzTest
    void decodeFailsOnlyWithFormatErrorsAndRoundTrips(byte @NotNull [] input) throws Exception {
        List<IpBan> bans;
        try {
            bans = IpBanStore.decode(input);
        } catch (IpBanStore.FormatException expected) {
            return;
        }
        assertTrue(bans.size() <= IpBanStore.MAX_ENTRIES);
        assertEquals(bans, IpBanStore.decode(IpBanStore.encode(bans)));
    }
}
