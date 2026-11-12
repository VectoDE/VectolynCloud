// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.cluster.PlacementPlanner.ServiceRequest;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.ServiceId;

/**
 * The head's pure decision of what happens to the services of a lost member
 * ({@code docs/cloud/cluster.md#failover}), without any I/O or clock:
 * <ul>
 *   <li>its <b>dynamic</b> services get a new node each by the rules of {@link PlacementPlanner},
 *       placed one after the other, never on the lost node;</li>
 *   <li>its <b>static</b> services wait for the node: their world lives there, and moving a static
 *       service with its world is a step of its own;</li>
 *   <li>a service whose task the configuration no longer has is dropped: there is nothing to
 *       start it from.</li>
 * </ul>
 */
public final class FailoverPlanner {

    private FailoverPlanner() {
    }

    /**
     * What happens to the services of a lost member.
     *
     * @param lost    the lost member
     * @param moved   one placement per dynamic service, in the order of its last status
     * @param waiting static services that wait for the member to come back
     * @param dropped services whose task no longer exists
     */
    public record Plan(NodeId lost, List<PlacementPlanner.Result> moved, List<ServiceId> waiting,
                       List<ServiceId> dropped) {

        /** Copies the lists. */
        public Plan {
            Objects.requireNonNull(lost, "lost");
            moved = List.copyOf(moved);
            waiting = List.copyOf(waiting);
            dropped = List.copyOf(dropped);
        }

        /**
         * The dynamic services that found no node now.
         *
         * @return how many of {@link #moved} are {@link PlacementPlanner.Unplaced}
         */
        public long unplaced() {
            return moved.stream().filter(PlacementPlanner.Unplaced.class::isInstance).count();
        }
    }

    /**
     * Plans the services of a lost member.
     *
     * @param snapshot  the head's configuration
     * @param lost      the lost member
     * @param last      its last status
     * @param reachable the current status of every node the head can reach; the lost one is
     *                  ignored if it is in it
     * @return the plan
     */
    public static Plan plan(ConfigSnapshot snapshot, NodeId lost, NodeStatus last, Map<NodeId, NodeStatus> reachable) {
        Objects.requireNonNull(lost, "lost");
        Objects.requireNonNull(last, "last");
        return planAll(snapshot, Map.of(lost, last), reachable).getFirst();
    }

    /**
     * Plans the services of several members lost at the same time, one member after the other:
     * none of them gets a service, and each member's placements count for the next one.
     *
     * @param snapshot  the head's configuration
     * @param lost      the last status of every lost member, in the order to plan them
     * @param reachable the current status of every node the head can reach; lost ones are ignored
     * @return one plan per lost member, in the same order
     */
    public static List<Plan> planAll(ConfigSnapshot snapshot, Map<NodeId, NodeStatus> lost,
                                     Map<NodeId, NodeStatus> reachable) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<NodeId, NodeStatus> planned = new HashMap<>(reachable);
        planned.keySet().removeAll(lost.keySet());
        Set<NodeId> excluded = new HashSet<>(lost.keySet());
        List<Plan> plans = new ArrayList<>(lost.size());
        for (Map.Entry<NodeId, NodeStatus> entry : lost.entrySet()) {
            List<ServiceRequest> dynamic = new ArrayList<>();
            List<ServiceId> waiting = new ArrayList<>();
            List<ServiceId> dropped = new ArrayList<>();
            for (RunningService service : entry.getValue().services()) {
                if (!placeable(snapshot, service.task())) {
                    dropped.add(service.id());
                } else if (service.dynamic()) {
                    dynamic.add(new ServiceRequest(service.id(), service.task()));
                } else {
                    waiting.add(service.id());
                }
            }
            List<PlacementPlanner.Result> moved = PlacementPlanner.placeAll(snapshot, dynamic, planned, excluded);
            for (int i = 0; i < moved.size(); i++) {
                if (moved.get(i) instanceof PlacementPlanner.Placed placed) {
                    ServiceRequest request = dynamic.get(i);
                    planned.put(placed.node(), planned.get(placed.node()).with(new RunningService(request.id(),
                            request.task(), true, PlacementPlanner.heapBytes(snapshot, request.task()))));
                }
            }
            plans.add(new Plan(entry.getKey(), moved, waiting, dropped));
        }
        return List.copyOf(plans);
    }

    /** Whether the task and its group exist, so the service can be started somewhere else. */
    private static boolean placeable(ConfigSnapshot snapshot, String task) {
        var config = snapshot.tasks().get(task);
        return config != null && snapshot.groups().containsKey(config.group());
    }
}
