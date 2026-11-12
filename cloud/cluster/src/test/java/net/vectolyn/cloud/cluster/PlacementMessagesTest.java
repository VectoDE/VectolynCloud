// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ProtocolException;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** The node status a member reports: round trips, limits, invalid values. */
class PlacementMessagesTest {

    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    void roundTrip() {
        NodeStatus status = new NodeStatus(true, 8 * GB, 5 * GB, List.of(
                new RunningService(new ServiceId("lobby-1"), "lobby", false, GB),
                new RunningService(new ServiceId("bedwars-12"), "bedwars_4x4", true, 2 * GB)));
        assertEquals(status, NodeStatus.read(new PacketReader(status.write())));
        assertEquals(3 * GB, status.declared());
        assertEquals(1, status.servicesOf("lobby"));
        assertEquals(0, status.servicesOf("game"));
        assertEquals(NodeStatus.UNKNOWN, NodeStatus.read(new PacketReader(NodeStatus.UNKNOWN.write())));
    }

    @Test
    void aFullStatusFitsIntoOneAnswer() {
        List<RunningService> services = new ArrayList<>();
        String longId = "s".repeat(60);
        for (int i = 0; i < PlacementMessages.MAX_SERVICES; i++) {
            services.add(new RunningService(new ServiceId(longId + i % 1000), "t".repeat(32), true,
                    PlacementMessages.MAX_BYTES));
        }
        byte[] bytes = new NodeStatus(true, PlacementMessages.MAX_BYTES, PlacementMessages.MAX_BYTES, services).write();
        assertTrue(bytes.length <= 64 * 1024, bytes.length + " bytes");
    }

    @Test
    void withCountsANewService() {
        NodeStatus status = new NodeStatus(true, 8 * GB, 3 * GB, List.of());
        NodeStatus more = status.with(new RunningService(new ServiceId("lobby-1"), "lobby", true, 2 * GB));
        assertEquals(2 * GB, more.declared());
        assertEquals(GB, more.free());
        assertEquals(0, more.with(new RunningService(new ServiceId("lobby-2"), "lobby", true, 2 * GB)).free());
        assertEquals(0, NodeStatus.UNKNOWN.with(new RunningService(new ServiceId("x"), "x", true, GB)).free());
    }

    @Test
    void invalidValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new NodeStatus(false, 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NodeStatus(true, -1, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NodeStatus(true, PlacementMessages.MAX_BYTES + 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunningService(new ServiceId("lobby-1"), "Lobby", true, GB));
        assertThrows(IllegalArgumentException.class,
                () -> new RunningService(new ServiceId("lobby-1"), "lobby", true, -1));

        // On the wire: a task name that breaks the rules, a negative byte count, trailing bytes.
        PacketWriter badTask = new PacketWriter().writeBoolean(true).writeVarLong(GB).writeVarLong(GB).writeVarInt(1)
                .writeString("lobby-1", 64).writeString("../x", 32).writeBoolean(true).writeVarLong(GB);
        assertThrows(ProtocolException.class, () -> NodeStatus.read(new PacketReader(badTask.toByteArray())));
        byte[] negative = new PacketWriter().writeBoolean(true).writeVarLong(-5).writeVarLong(0).writeVarInt(0)
                .toByteArray();
        assertThrows(ProtocolException.class, () -> NodeStatus.read(new PacketReader(negative)));
        byte[] trailing = new PacketWriter().writeBytes(NodeStatus.UNKNOWN.write()).writeByte(0).toByteArray();
        assertThrows(ProtocolException.class, () -> NodeStatus.read(new PacketReader(trailing)));
        byte[] tooMany = new PacketWriter().writeBoolean(false).writeVarLong(0).writeVarLong(0)
                .writeVarInt(PlacementMessages.MAX_SERVICES + 1).toByteArray();
        assertThrows(ProtocolException.class, () -> NodeStatus.read(new PacketReader(tooMany)));
    }
}
