// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.node.scaling.ScalingPlanner;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** What a node reports to the cluster head for the placement. */
class ClusterStatusReportTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    private static ConfigSnapshot snapshot(String maxMemory) {
        NodeConfig node = NodeConfig.defaults().withMaxMemory(maxMemory == null ? null : MemorySize.parse(maxMemory));
        GroupConfig lobby = new GroupConfig(GroupConfig.SCHEMA_VERSION, "Lobby", ServerSoftware.PAPER, "26.3",
                JavaSelection.AUTO, new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("1G")), List.of(),
                CombatSystem.MODERN);
        return ConfigSnapshot.of(CloudConfig.defaults(), new NodesConfig(NodesConfig.SCHEMA_VERSION, List.of(node)),
                Map.of("lobby", lobby), Map.of());
    }

    private static ServiceInfo service(String id, String group, TaskMode mode, ServiceState state) {
        return new ServiceInfo(new ServiceId(id), group, "lobby", 1, mode, state, 30000, Path.of("x"),
                state.hasProcess() ? OptionalLong.of(1) : OptionalLong.empty(), Optional.empty(), OptionalInt.empty(), 0);
    }

    @Test
    void servicesWithAProcessOrAboutToGetOneCount() {
        List<ServiceInfo> services = List.of(
                service("lobby-1", "lobby", TaskMode.STATIC, ServiceState.RUNNING),
                service("lobby-2", "lobby", TaskMode.DYNAMIC, ServiceState.PREPARED),
                service("lobby-3", "lobby", TaskMode.DYNAMIC, ServiceState.STOPPED),
                service("lobby-4", "lobby", TaskMode.DYNAMIC, ServiceState.CRASHED),
                service("gone-1", "gone", TaskMode.DYNAMIC, ServiceState.STARTING));
        NodeStatus status = ClusterStatusReport.of(snapshot("8G"), "node-1", services,
                Optional.of(new ScalingPlanner.Memory(10 * GB, 16 * GB)));
        assertEquals(List.of(
                new RunningService(new ServiceId("lobby-1"), "lobby", false, GB),
                new RunningService(new ServiceId("lobby-2"), "lobby", true, GB),
                new RunningService(new ServiceId("gone-1"), "lobby", true, 0)), status.services());
        assertTrue(status.memoryKnown());
        assertEquals(8 * GB, status.budget(), "max-memory of the node");
        assertEquals(6 * GB, status.free());
    }

    @Test
    void withoutAMemoryReadingTheMemoryIsUnknown() {
        NodeStatus status = ClusterStatusReport.of(snapshot(null), "node-1",
                List.of(service("lobby-1", "lobby", TaskMode.DYNAMIC, ServiceState.RUNNING)), Optional.empty());
        assertFalse(status.memoryKnown());
        assertEquals(0, status.budget());
        assertEquals(1, status.services().size());
    }

    @Test
    void theBudgetIsTheAutoscalersBudget() {
        ScalingPlanner.Memory memory = new ScalingPlanner.Memory(2 * GB, 16 * GB, 512 * MB);
        NodeStatus status = ClusterStatusReport.of(snapshot(null), "node-1", List.of(), Optional.of(memory));
        assertEquals(ScalingPlanner.budget(snapshot(null), "node-1", memory), status.budget());
        assertEquals(14 * GB, status.free());
    }
}
