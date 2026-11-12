// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.cluster.PlacementPlanner.Placed;
import net.vectolyn.cloud.cluster.PlacementPlanner.Refusal;
import net.vectolyn.cloud.cluster.PlacementPlanner.Unplaced;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.PortRange;
import net.vectolyn.cloud.config.model.ScalingConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TimeSpan;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** What happens to the services of a lost member, and when a member counts as lost. */
class FailoverPlannerTest {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");
    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    private static ConfigSnapshot snapshot() {
        ScalingConfig scaling = new ScalingConfig(0, 0, 10, 50, 80, 40, TimeSpan.parse("5m"), TimeSpan.parse("60s"));
        return ConfigSnapshot.of(CloudConfig.defaults(), new NodesConfig(NodesConfig.SCHEMA_VERSION, List.of(
                        new NodeConfig("node-1", "10.0.0.1", 7010, new PortRange(30000, 30999)),
                        new NodeConfig("node-2", "10.0.0.2", 7010, new PortRange(30000, 30999)),
                        new NodeConfig("node-3", "10.0.0.3", 7010, new PortRange(30000, 30999)))),
                Map.of("lobby", ClusterMirrorTest.group("1G")),
                Map.of("lobby", new TaskConfig(TaskConfig.SCHEMA_VERSION, "lobby", scaling, List.of())));
    }

    private static RunningService service(String id, boolean dynamic) {
        return new RunningService(new ServiceId(id), id.substring(0, id.indexOf('-')), dynamic, GB);
    }

    @Test
    void dynamicServicesMoveStaticOnesWaitUnknownOnesAreDropped() {
        NodeStatus last = new NodeStatus(true, 8 * GB, 4 * GB, List.of(
                service("lobby-1", true), service("lobby-2", false), service("lobby-3", true),
                service("old-1", true)));
        Map<NodeId, NodeStatus> reachable = new LinkedHashMap<>();
        reachable.put(N1, new NodeStatus(true, 4 * GB, 4 * GB, List.of()));
        reachable.put(N2, new NodeStatus(true, 99 * GB, 99 * GB, List.of()));
        reachable.put(N3, new NodeStatus(true, 2 * GB, 2 * GB, List.of()));

        FailoverPlanner.Plan plan = FailoverPlanner.plan(snapshot(), N2, last, reachable);

        assertEquals(N2, plan.lost());
        // The lost node is never chosen, even if its old status is still in the map; spread puts
        // the two dynamic lobbies on two nodes, node-1 first (more budget left).
        assertEquals(2, plan.moved().size());
        Placed first = assertInstanceOf(Placed.class, plan.moved().get(0));
        Placed second = assertInstanceOf(Placed.class, plan.moved().get(1));
        assertEquals(N1, first.node());
        assertEquals(N3, second.node());
        assertEquals(Refusal.EXCLUDED, first.refused().get(N2));
        assertEquals(List.of(new ServiceId("lobby-2")), plan.waiting());
        assertEquals(List.of(new ServiceId("old-1")), plan.dropped());
        assertEquals(0, plan.unplaced());
    }

    @Test
    void servicesWithoutRoomStayUnplaced() {
        NodeStatus last = new NodeStatus(true, 8 * GB, 4 * GB, List.of(service("lobby-1", true),
                service("lobby-2", true)));
        Map<NodeId, NodeStatus> reachable = Map.of(N1, new NodeStatus(true, GB, GB, List.of()));
        FailoverPlanner.Plan plan = FailoverPlanner.plan(snapshot(), N2, last, reachable);
        assertEquals(N1, assertInstanceOf(Placed.class, plan.moved().get(0)).node());
        Unplaced rest = assertInstanceOf(Unplaced.class, plan.moved().get(1));
        assertEquals(Map.of(N1, Refusal.MEMORY_BUDGET, N2, Refusal.EXCLUDED, N3, Refusal.UNREACHABLE), rest.refused());
        assertEquals(1, plan.unplaced());
    }

    @Test
    void twoMembersLostAtOnceNeitherGetsAServiceAndPlacementsAddUp() {
        Map<NodeId, NodeStatus> lost = new LinkedHashMap<>();
        lost.put(N2, new NodeStatus(true, 8 * GB, 4 * GB, List.of(service("lobby-1", true), service("lobby-2", true))));
        lost.put(N3, new NodeStatus(true, 8 * GB, 4 * GB, List.of(service("lobby-3", true))));
        // node-1 has room for exactly two lobbies; both lost nodes still look fine in the map.
        Map<NodeId, NodeStatus> reachable = new LinkedHashMap<>();
        reachable.put(N1, new NodeStatus(true, 2 * GB, 2 * GB, List.of()));
        reachable.put(N2, new NodeStatus(true, 99 * GB, 99 * GB, List.of()));
        reachable.put(N3, new NodeStatus(true, 99 * GB, 99 * GB, List.of()));

        List<FailoverPlanner.Plan> plans = FailoverPlanner.planAll(snapshot(), lost, reachable);

        assertEquals(List.of(N2, N3), plans.stream().map(FailoverPlanner.Plan::lost).toList());
        assertEquals(N1, assertInstanceOf(Placed.class, plans.get(0).moved().get(0)).node());
        assertEquals(N1, assertInstanceOf(Placed.class, plans.get(0).moved().get(1)).node());
        Unplaced third = assertInstanceOf(Unplaced.class, plans.get(1).moved().get(0));
        assertEquals(Map.of(N1, Refusal.MEMORY_BUDGET, N2, Refusal.EXCLUDED, N3, Refusal.EXCLUDED), third.refused());
    }

    @Test
    void aMemberIsLostAfterTheGraceAndBackWhenItConnects() {
        MemberWatch watch = new MemberWatch(Duration.ofSeconds(30));
        Set<NodeId> seen = Set.of(N2, N3);
        assertTrue(watch.update(T0, seen, Set.of(N2, N3)).isEmpty());
        // node-2's session ends; 29 s later it is not lost yet.
        assertTrue(watch.update(T0.plusSeconds(1), seen, Set.of(N3)).isEmpty());
        assertTrue(watch.update(T0.plusSeconds(30), seen, Set.of(N3)).isEmpty());
        assertEquals(List.of(N2), watch.update(T0.plusSeconds(31), seen, Set.of(N3)).lost());
        assertEquals(Set.of(N2), watch.lost());
        // Reported once only.
        assertTrue(watch.update(T0.plusSeconds(60), seen, Set.of(N3)).isEmpty());
        // It comes back.
        MemberWatch.Change back = watch.update(T0.plusSeconds(61), seen, Set.of(N2, N3));
        assertEquals(List.of(N2), back.back());
        assertTrue(watch.lost().isEmpty());
    }

    @Test
    void aShortBreakIsNoLoss() {
        MemberWatch watch = new MemberWatch(Duration.ofSeconds(30));
        Set<NodeId> seen = Set.of(N2);
        watch.update(T0, seen, Set.of());
        watch.update(T0.plusSeconds(20), seen, Set.of(N2));
        // A new break starts its own grace.
        assertTrue(watch.update(T0.plusSeconds(40), seen, Set.of()).isEmpty());
        assertTrue(watch.update(T0.plusSeconds(69), seen, Set.of()).isEmpty());
        assertEquals(List.of(N2), watch.update(T0.plusSeconds(70), seen, Set.of()).lost());
    }

    @Test
    void onlySeenMembersCanBeLostAndRemovedOnesAreForgotten() {
        MemberWatch watch = new MemberWatch(Duration.ZERO);
        assertTrue(watch.update(T0, Set.of(), Set.of()).isEmpty(), "never had a session: not lost");
        assertEquals(List.of(N2), watch.update(T0, Set.of(N2), Set.of()).lost());
        watch.update(T0.plusSeconds(1), Set.of(), Set.of());
        assertTrue(watch.lost().isEmpty());
    }
}
