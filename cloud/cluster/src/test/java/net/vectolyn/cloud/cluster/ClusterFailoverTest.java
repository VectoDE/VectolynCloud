// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.cluster.PlacementPlanner.Placed;
import net.vectolyn.cloud.cluster.PlacementPlanner.Refusal;
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
 * A simulated node failure: a head and a member on loopback with a real CA and mutual TLS, the
 * member's session is killed, and the head marks it lost and plans its services on the nodes left.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ClusterFailoverTest {

    private static final NodeId HEAD = new NodeId("node-1");
    private static final NodeId MEMBER = new NodeId("node-2");
    private static final long GB = 1024L * 1024L * 1024L;

    @TempDir
    Path dir;

    private TestNode head;
    private TestNode member;
    private int port;
    private final List<FailoverPlanner.Plan> lost = new CopyOnWriteArrayList<>();
    private final List<NodeId> back = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        head = new TestNode(dir.resolve("head"), "node-1", "1.0.0");
        member = new TestNode(dir.resolve("member"), "node-2", "1.0.0");
        port = TestNode.freePort();
        head.config.apply(List.of(new PutGroup("lobby", ClusterMirrorTest.group("1G")),
                new PutTask("lobby", ClusterMirrorTest.task().withNodes(List.of()))));
        head.cluster.failoverTimings(Duration.ofMillis(500), Duration.ofMillis(100));
        head.cluster.events(new ClusterService.Events() {
            @Override
            public void memberConnected(NodeId node) {
                // not needed here
            }

            @Override
            public void memberDisconnected(NodeId node) {
                // not needed here
            }

            @Override
            public void versionMismatch(String own, String headVersion) {
                // not needed here
            }

            @Override
            public void memberLost(FailoverPlanner.Plan plan) {
                lost.add(plan);
            }

            @Override
            public void memberBack(NodeId node) {
                back.add(node);
            }
        });
        head.cluster.init("127.0.0.1", port, null);
        head.cluster.statusSource(() -> new NodeStatus(true, 8 * GB, 8 * GB, List.of()));
        // Two dynamic lobbies and a static one on the member.
        member.cluster.statusSource(() -> new NodeStatus(true, 8 * GB, 5 * GB, List.of(
                new RunningService(new ServiceId("lobby-2"), "lobby", true, GB),
                new RunningService(new ServiceId("lobby-3"), "lobby", true, GB),
                new RunningService(new ServiceId("lobby-1"), "lobby", false, GB))));
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
            Thread.sleep(20);
        }
    }

    @Test
    void aLostMemberIsMarkedAndItsDynamicServicesArePlacedElsewhere() throws Exception {
        join();
        await(() -> head.cluster.nodeReports().containsKey(MEMBER)
                && head.config.snapshot().nodes().find("node-2").isPresent());
        assertTrue(lost.isEmpty());

        // The member's process dies: its session ends.
        member.close();
        await(() -> !lost.isEmpty());

        FailoverPlanner.Plan plan = lost.getFirst();
        assertEquals(MEMBER, plan.lost());
        assertEquals(java.util.Set.of(MEMBER), head.cluster.lostMembers());
        assertEquals(2, plan.moved().size());
        for (PlacementPlanner.Result result : plan.moved()) {
            Placed placed = assertInstanceOf(Placed.class, result);
            assertEquals(HEAD, placed.node());
            assertEquals(Refusal.EXCLUDED, placed.refused().get(MEMBER));
        }
        assertEquals(List.of(new ServiceId("lobby-1")), plan.waiting(), "the static one waits for its node");
        assertEquals(1, lost.size(), "reported once");
        // A lost member is not placed on.
        assertEquals(HEAD, assertInstanceOf(Placed.class, head.cluster.place("lobby")).node());

        // It comes back with a restart.
        member.open("1.0.0", java.time.Clock.systemUTC());
        member.cluster.statusSource(() -> new NodeStatus(true, 8 * GB, 8 * GB, List.of()));
        member.cluster.start();
        await(() -> back.contains(MEMBER));
        assertTrue(head.cluster.lostMembers().isEmpty());
    }
}
