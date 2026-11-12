// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.cluster.PlacementPlanner.Placed;
import net.vectolyn.cloud.cluster.PlacementPlanner.Refusal;
import net.vectolyn.cloud.cluster.PlacementPlanner.Result;
import net.vectolyn.cloud.cluster.PlacementPlanner.ServiceRequest;
import net.vectolyn.cloud.cluster.PlacementPlanner.Unplaced;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.PortRange;
import net.vectolyn.cloud.config.model.ScalingConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TimeSpan;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** The head's choice of a node for a new service: candidates, memory, spread, order. */
class PlacementPlannerTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    /** heap.max of the lobby group (ClusterMirrorTest.group). */
    private static final long LOBBY_HEAP = 1024 * MB;
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");

    private static ConfigSnapshot snapshot(List<String> lobbyNodes) {
        List<NodeConfig> nodes = new ArrayList<>();
        int port = 7010;
        for (String id : List.of("node-1", "node-2", "node-3")) {
            nodes.add(new NodeConfig(id, "10.0.0." + port % 100, port++, new PortRange(30000, 30999)));
        }
        GroupConfig lobby = ClusterMirrorTest.group("1G");
        GroupConfig game = ClusterMirrorTest.group("2G");
        ScalingConfig scaling = new ScalingConfig(0, 0, 10, 50, 80, 40, TimeSpan.parse("5m"), TimeSpan.parse("60s"));
        return ConfigSnapshot.of(CloudConfig.defaults(), new NodesConfig(NodesConfig.SCHEMA_VERSION, nodes),
                Map.of("lobby", lobby, "game", game),
                Map.of("lobby", new TaskConfig(TaskConfig.SCHEMA_VERSION, "lobby", scaling, lobbyNodes),
                        "game", new TaskConfig(TaskConfig.SCHEMA_VERSION, "game", scaling, List.of())));
    }

    private static NodeStatus status(long budget, long free, RunningService... services) {
        return new NodeStatus(true, budget, free, List.of(services));
    }

    private static RunningService lobby(int index) {
        return new RunningService(new ServiceId("lobby-" + index), "lobby", true, LOBBY_HEAP);
    }

    private static RunningService game(int index) {
        return new RunningService(new ServiceId("game-" + index), "game", true, 2 * GB);
    }

    private static Map<NodeId, NodeStatus> nodes(Object... pairs) {
        Map<NodeId, NodeStatus> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((NodeId) pairs[i], (NodeStatus) pairs[i + 1]);
        }
        return map;
    }

    private static NodeId placed(Result result) {
        return assertInstanceOf(Placed.class, result, result.toString()).node();
    }

    @Test
    void theNodeWithTheFewestServicesOfTheTaskWins() {
        // node-1 has far more room, but already a lobby: the new one goes elsewhere.
        Map<NodeId, NodeStatus> reachable = nodes(
                N1, status(32 * GB, 30 * GB, lobby(1)),
                N2, status(4 * GB, 4 * GB, game(1)),
                N3, status(8 * GB, 8 * GB, lobby(2), lobby(3)));
        Result result = PlacementPlanner.place(snapshot(List.of()), "lobby", reachable, Set.of());
        assertEquals(N2, placed(result));
        assertTrue(result.refused().isEmpty(), "every node fits; losing the comparison is no refusal");
    }

    @Test
    void thenTheMostBudgetLeftThenTheLowestId() {
        Map<NodeId, NodeStatus> reachable = nodes(
                N1, status(8 * GB, 8 * GB, game(1)),
                N2, status(8 * GB, 8 * GB),
                N3, status(8 * GB, 8 * GB));
        // node-2 and node-3 have 7G left, node-1 5G.
        assertEquals(N2, placed(PlacementPlanner.place(snapshot(List.of()), "lobby", reachable, Set.of())));
        // The order of the map does not matter.
        Map<NodeId, NodeStatus> reversed = nodes(N3, reachable.get(N3), N2, reachable.get(N2), N1, reachable.get(N1));
        assertEquals(N2, placed(PlacementPlanner.place(snapshot(List.of()), "lobby", reversed, Set.of())));
        // Free memory right now does not rank, it only has to be enough.
        Map<NodeId, NodeStatus> busy = nodes(N1, status(16 * GB, 2 * GB), N2, status(8 * GB, 8 * GB));
        assertEquals(N1, placed(PlacementPlanner.place(snapshot(List.of()), "lobby", busy, Set.of())));
    }

    @Test
    void onlyTheNodesOfTheTaskAreCandidates() {
        Map<NodeId, NodeStatus> reachable = nodes(
                N1, status(32 * GB, 32 * GB),
                N2, status(4 * GB, 4 * GB),
                N3, status(4 * GB, 4 * GB, lobby(1)));
        Result result = PlacementPlanner.place(snapshot(List.of("node-3", "node-2")), "lobby", reachable, Set.of());
        assertEquals(N2, placed(result));
        // node-3 still fits (it only lost on spread), so nothing is refused; node-1 is no candidate.
        assertTrue(result.refused().isEmpty());

        Result none = PlacementPlanner.place(snapshot(List.of("node-3")), "lobby",
                nodes(N1, status(32 * GB, 32 * GB)), Set.of());
        Unplaced unplaced = assertInstanceOf(Unplaced.class, none);
        assertEquals(Map.of(N3, Refusal.UNREACHABLE), unplaced.refused());
    }

    @Test
    void everyRefusalIsNamed() {
        Map<NodeId, NodeStatus> reachable = nodes(
                N1, NodeStatus.UNKNOWN,
                N2, status(2 * GB, 8 * GB, lobby(1), lobby(2)),
                N3, status(8 * GB, 512 * MB));
        Unplaced result = assertInstanceOf(Unplaced.class,
                PlacementPlanner.place(snapshot(List.of()), "lobby", reachable, Set.of()));
        assertEquals(Map.of(N1, Refusal.MEMORY_UNKNOWN, N2, Refusal.MEMORY_BUDGET, N3, Refusal.MEMORY_FREE),
                result.refused());
        assertEquals(List.of(N1, N2, N3), List.copyOf(result.refused().keySet()), "in the order of nodes.yml");

        Unplaced gone = assertInstanceOf(Unplaced.class,
                PlacementPlanner.place(snapshot(List.of()), "lobby", nodes(N1, status(8 * GB, 8 * GB)), Set.of(N1)));
        assertEquals(Map.of(N1, Refusal.EXCLUDED, N2, Refusal.UNREACHABLE, N3, Refusal.UNREACHABLE), gone.refused());
    }

    @Test
    void theBudgetCountsMaximumHeapsExactly() {
        // 3G budget, 2G declared: exactly 1G is left, which is the lobby's heap.
        Map<NodeId, NodeStatus> exact = nodes(N1, status(3 * GB, LOBBY_HEAP, game(1)));
        assertEquals(N1, placed(PlacementPlanner.place(snapshot(List.of("node-1")), "lobby", exact, Set.of())));
        Map<NodeId, NodeStatus> short1 = nodes(N1, status(3 * GB - 1, 8 * GB, game(1)));
        assertEquals(Map.of(N1, Refusal.MEMORY_BUDGET),
                PlacementPlanner.place(snapshot(List.of("node-1")), "lobby", short1, Set.of()).refused());
        Map<NodeId, NodeStatus> notFree = nodes(N1, status(8 * GB, LOBBY_HEAP - 1));
        assertEquals(Map.of(N1, Refusal.MEMORY_FREE),
                PlacementPlanner.place(snapshot(List.of("node-1")), "lobby", notFree, Set.of()).refused());
    }

    @Test
    void placeAllCountsTheServicesItPlacedBefore() {
        Map<NodeId, NodeStatus> reachable = nodes(
                N1, status(3 * GB, 3 * GB),
                N2, status(3 * GB, 3 * GB));
        List<ServiceRequest> four = List.of(
                new ServiceRequest(new ServiceId("lobby-1"), "lobby"),
                new ServiceRequest(new ServiceId("lobby-2"), "lobby"),
                new ServiceRequest(new ServiceId("lobby-3"), "lobby"),
                new ServiceRequest(new ServiceId("game-1"), "game"));
        List<Result> results = PlacementPlanner.placeAll(snapshot(List.of("node-1", "node-2")), four, reachable,
                Set.of(N3));
        // Spread: one lobby each, the third to the lower id; then 2G free on node-2 only.
        assertEquals(N1, placed(results.get(0)));
        assertEquals(N2, placed(results.get(1)));
        assertEquals(N1, placed(results.get(2)));
        assertEquals(N2, placed(results.get(3)));
        // node-1 has room for one more lobby (2G declared of 3G, 1G free), then none fits any more.
        List<ServiceRequest> six = new ArrayList<>(four);
        six.add(new ServiceRequest(new ServiceId("lobby-4"), "lobby"));
        six.add(new ServiceRequest(new ServiceId("lobby-5"), "lobby"));
        List<Result> more = PlacementPlanner.placeAll(snapshot(List.of("node-1", "node-2")), six, reachable, Set.of());
        assertEquals(N1, placed(more.get(4)));
        Unplaced last = assertInstanceOf(Unplaced.class, more.get(5));
        assertEquals(Map.of(N1, Refusal.MEMORY_BUDGET, N2, Refusal.MEMORY_BUDGET), last.refused());
        assertEquals(reachable, nodes(N1, status(3 * GB, 3 * GB), N2, status(3 * GB, 3 * GB)), "input untouched");
    }

    @Test
    void anUnknownTaskOrGroupIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PlacementPlanner.place(snapshot(List.of()), "missing", Map.of(), Set.of()));
        ConfigSnapshot noGroup = snapshot(List.of());
        ConfigSnapshot broken = ConfigSnapshot.of(noGroup.cloud(), noGroup.nodes(), Map.of(), noGroup.tasks());
        assertThrows(IllegalArgumentException.class,
                () -> PlacementPlanner.place(broken, "lobby", Map.of(), Set.of()));
    }
}
