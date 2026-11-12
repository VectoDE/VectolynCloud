// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.vectolyn.cloud.cluster.PlacementMessages;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.node.scaling.ScalingPlanner;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceState;

/**
 * What this node tells the cluster head about itself ({@link NodeStatus},
 * {@code docs/cloud/cluster.md#placement}): the memory budget and the free memory by the rules of
 * the autoscaler ({@link ScalingPlanner#budget}), and every service with a process or about to get
 * one, with the maximum heap of its group.
 */
final class ClusterStatusReport {

    private ClusterStatusReport() {
    }

    /**
     * The status of this node now.
     *
     * @param snapshot the configuration
     * @param node     this node's id
     * @param services the services of this node
     * @param memory   the memory reading of the monitor, if there is one
     * @return the status
     */
    static NodeStatus of(ConfigSnapshot snapshot, String node, List<ServiceInfo> services,
                         Optional<ScalingPlanner.Memory> memory) {
        List<RunningService> running = new ArrayList<>();
        for (ServiceInfo service : services) {
            if (running.size() == PlacementMessages.MAX_SERVICES) {
                break;
            }
            if (service.state() != ServiceState.PREPARED && !service.state().hasProcess()) {
                continue;
            }
            GroupConfig group = snapshot.groups().get(service.group());
            long heap = group == null ? 0 : group.heap().max().megabytes() * 1024L * 1024L;
            running.add(new RunningService(service.id(), service.task(), service.mode() == TaskMode.DYNAMIC,
                    Math.min(heap, PlacementMessages.MAX_BYTES)));
        }
        if (memory.isEmpty()) {
            return new NodeStatus(false, 0, 0, running);
        }
        ScalingPlanner.Memory reading = memory.get();
        long budget = ScalingPlanner.budget(snapshot, node, reading);
        long free = Math.max(0, reading.total() - reading.used());
        return new NodeStatus(true, Math.min(budget, PlacementMessages.MAX_BYTES),
                Math.min(free, PlacementMessages.MAX_BYTES), running);
    }
}
