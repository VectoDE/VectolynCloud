// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.ProtocolException;

/**
 * Fuzz target for the node status a head reads from every member. Stored inputs live in
 * {@code PlacementMessagesFuzzTestInputs/<method>/}; the nightly workflow fuzzes it on Linux.
 */
class PlacementMessagesFuzzTest {

    /** Any bytes as a status: a value that decodes writes back to the same bytes, nothing else escapes. */
    @FuzzTest
    void nodeStatusDecodesOrThrowsProtocolException(byte @NotNull [] input) {
        try {
            assertArrayEquals(input, NodeStatus.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected for most inputs
        }
    }
}
