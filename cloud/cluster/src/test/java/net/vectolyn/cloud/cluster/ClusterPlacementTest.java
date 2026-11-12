// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.cluster.PlacementPlanner.Placed;
import net.vectolyn.cloud.cluster.PlacementPlanner.Refusal;
import net.vectolyn.cloud.cluster.PlacementPlanner.Unplaced;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A head and a member on loopback with a real CA and mutual TLS: the member reports its status on
 * the session, the head places by it.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ClusterPlacementTest {

    private static final NodeId HEAD = new NodeId("node-1");
    private static final NodeId MEMBER = new NodeId("node-2");
    private static final long GB = 1024L * 1024L * 1024L;

    @TempDir
    Path dir;

    private TestNode head;
    private TestNode member;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        head = new TestNode(dir.resolve("head"), "node-1", "1.0.0");
        member = new TestNode(dir.resolve("member"), "node-2", "1.0.0");
        port = TestNode.freePort();
        // A task without a node list may run on every node of nodes.yml.
        head.config.apply(List.of(new PutGroup("lobby", ClusterMirrorTest.group("1G")),
                new PutTask("lobby", ClusterMirrorTest.task().withNodes(List.of()))));
        head.cluster.init("127.0.0.1", port, null);
        // The head runs a lobby already and has little room left.
        head.cluster.statusSource(() -> new NodeStatus(true, 2 * GB, 4 * GB,
                List.of(new RunningService(new ServiceId("lobby-1"), "lobby", true, GB))));
        member.cluster.statusSource(() -> new NodeStatus(true, 8 * GB, 8 * GB, List.of()));
    }

    @AfterEach
    void tearDown() {
        member.close();
        head.close();
    }

    private void join() throws Exception {
        String token = head.cluster.createToken(MEMBER, Duration.ofMinutes(10)).token().reveal();
        String pin = head.cluster.status().pin().orElseThrow().toString();
        member.cluster.join("127.0.0.1", port, HEAD, token, pin);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void theHeadPlacesByWhatTheMemberReports() throws Exception {
        // Before any member: only the head, which has 1G of its 2G budget left.
        assertEquals(HEAD, assertInstanceOf(Placed.class, head.cluster.place("lobby")).node());

        join();
        // The member reports right after its session starts; the head added it to nodes.yml.
        await(() -> head.cluster.nodeReports().containsKey(MEMBER)
                && head.config.snapshot().nodes().find("node-2").isPresent());
        ClusterService.NodeReport report = head.cluster.nodeReports().get(MEMBER);
        assertTrue(report.connected());
        assertEquals(8 * GB, report.status().budget());
        assertEquals(List.of(HEAD, MEMBER), List.copyOf(head.cluster.nodeReports().keySet()));

        // The member has no lobby yet and more room: it gets the next one.
        assertEquals(MEMBER, assertInstanceOf(Placed.class, head.cluster.place("lobby")).node());

        // A member's status changes with its next report.
        member.cluster.statusSource(() -> new NodeStatus(true, 8 * GB, 512L * 1024 * 1024, List.of()));
        member.close();
        await(() -> !head.cluster.nodeReports().get(MEMBER).connected());
        // A member without a session is not placed on; its last report is kept.
        Placed back = assertInstanceOf(Placed.class, head.cluster.place("lobby"));
        assertEquals(HEAD, back.node());
        assertEquals(Refusal.UNREACHABLE, back.refused().get(MEMBER));
        assertEquals(8 * GB, head.cluster.nodeReports().get(MEMBER).status().budget());

        // With the head full too, nothing fits.
        head.cluster.statusSource(() -> new NodeStatus(true, GB, 4 * GB,
                List.of(new RunningService(new ServiceId("lobby-1"), "lobby", true, GB))));
        Unplaced none = assertInstanceOf(Unplaced.class, head.cluster.place("lobby"));
        assertEquals(Refusal.MEMORY_BUDGET, none.refused().get(HEAD));
    }

    @Test
    void onlyTheHeadPlacesAndOnlyKnownTasks() throws Exception {
        assertThrows(ClusterException.class, () -> head.cluster.place("missing"));
        join();
        assertThrows(ClusterException.class, () -> member.cluster.place("lobby"));
        assertTrue(member.cluster.nodeReports().isEmpty());
        assertFalse(head.cluster.nodeReports().isEmpty());
    }
}
