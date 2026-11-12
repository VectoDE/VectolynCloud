// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.vectolyn.cloud.cluster.PlacementMessages.NodeStatus;
import net.vectolyn.cloud.cluster.PlacementMessages.RunningService;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.ServiceId;

/**
 * The head's pure choice of a node for a new service of a task, without any I/O or clock: it gets
 * the configuration and the last {@link NodeStatus} of every node it can reach. The rules are in
 * {@code docs/cloud/cluster.md#placement}:
 * <ol>
 *   <li>Candidates are the nodes of the task's {@code nodes:} list, or every node of
 *       {@code nodes.yml} if the list is empty; a node the caller rules out (a lost one) is
 *       {@link Refusal#EXCLUDED}.</li>
 *   <li>A candidate needs a current status ({@link Refusal#UNREACHABLE} otherwise) with a memory
 *       reading ({@link Refusal#MEMORY_UNKNOWN}), the maximum heaps of its services plus the new
 *       one must fit into its budget ({@link Refusal#MEMORY_BUDGET}), and the new maximum heap must
 *       be free right now ({@link Refusal#MEMORY_FREE}): the same memory rule as the autoscaler on
 *       one node.</li>
 *   <li>Of the nodes left, the one with the fewest services of the task wins (spread, so losing a
 *       node loses as few services of a task as possible), then the one with the most budget left
 *       after the new service, then the lowest node id, so the same input gives the same node.</li>
 * </ol>
 */
public final class PlacementPlanner {

    private PlacementPlanner() {
    }

    /** Why a node does not get the service. */
    public enum Refusal {
        /** The caller ruled it out, for example because it was lost. */
        EXCLUDED,
        /** The head has no current status of it (not connected, or it has not reported). */
        UNREACHABLE,
        /** The node has no memory reading. */
        MEMORY_UNKNOWN,
        /** The maximum heaps of its services and the new one exceed its budget. */
        MEMORY_BUDGET,
        /** Less memory is free on it right now than the new service's maximum heap. */
        MEMORY_FREE
    }

    /** The outcome for one service. */
    public sealed interface Result permits Placed, Unplaced {

        /**
         * The task the service belongs to.
         *
         * @return task name
         */
        String task();

        /**
         * Why each candidate that was not chosen did not get it; a chosen node is not in it.
         *
         * @return refusals by node, in the order of the candidates
         */
        Map<NodeId, Refusal> refused();
    }

    /**
     * The service goes to {@code node}.
     *
     * @param task    task name
     * @param node    the chosen node
     * @param refused why the other candidates were not chosen; a candidate that fits but lost the
     *                comparison is not in it
     */
    public record Placed(String task, NodeId node, Map<NodeId, Refusal> refused) implements Result {

        /** Copies the map. */
        public Placed {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(node, "node");
            refused = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(refused));
        }
    }

    /**
     * No node can take the service now.
     *
     * @param task    task name
     * @param refused why each candidate was refused; empty if the task has no candidate at all
     */
    public record Unplaced(String task, Map<NodeId, Refusal> refused) implements Result {

        /** Copies the map. */
        public Unplaced {
            Objects.requireNonNull(task, "task");
            refused = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(refused));
        }
    }

    /**
     * Chooses a node for one more service of {@code task}.
     *
     * @param snapshot  the head's configuration
     * @param task      the task
     * @param reachable the current status of every node the head can reach, the head included
     * @param excluded  nodes that must not get it
     * @return the node, or why none
     * @throws IllegalArgumentException if the task or its group does not exist
     */
    public static Result place(ConfigSnapshot snapshot, String task, Map<NodeId, NodeStatus> reachable,
                               Set<NodeId> excluded) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(reachable, "reachable");
        Objects.requireNonNull(excluded, "excluded");
        long heap = heapBytes(snapshot, task);
        Map<NodeId, Refusal> refused = new LinkedHashMap<>();
        List<NodeId> fitting = new ArrayList<>();
        for (NodeId node : candidates(snapshot, task)) {
            NodeStatus status = reachable.get(node);
            Refusal refusal;
            if (excluded.contains(node)) {
                refusal = Refusal.EXCLUDED;
            } else if (status == null) {
                refusal = Refusal.UNREACHABLE;
            } else if (!status.memoryKnown()) {
                refusal = Refusal.MEMORY_UNKNOWN;
            } else if (status.declared() + heap > status.budget()) {
                refusal = Refusal.MEMORY_BUDGET;
            } else if (status.free() < heap) {
                refusal = Refusal.MEMORY_FREE;
            } else {
                fitting.add(node);
                continue;
            }
            refused.put(node, refusal);
        }
        if (fitting.isEmpty()) {
            return new Unplaced(task, refused);
        }
        NodeId chosen = fitting.stream().min(Comparator
                .comparingInt((NodeId node) -> reachable.get(node).servicesOf(task))
                .thenComparing(node -> -(reachable.get(node).budget() - reachable.get(node).declared() - heap))
                .thenComparing(NodeId::value)).orElseThrow();
        return new Placed(task, chosen, refused);
    }

    /**
     * Places several new services one after the other, each counting the ones placed before it
     * (as if the chosen node already ran them), for example to move the dynamic services of a lost
     * node.
     *
     * @param snapshot  the head's configuration
     * @param services  the new services, by the task they belong to, in this order
     * @param reachable the current status of every node the head can reach
     * @param excluded  nodes that must not get any of them
     * @return one result per service, in the same order
     * @throws IllegalArgumentException if a task or its group does not exist
     */
    public static List<Result> placeAll(ConfigSnapshot snapshot, List<ServiceRequest> services,
                                        Map<NodeId, NodeStatus> reachable, Set<NodeId> excluded) {
        Map<NodeId, NodeStatus> planned = new HashMap<>(reachable);
        List<Result> results = new ArrayList<>(services.size());
        for (ServiceRequest request : services) {
            Result result = place(snapshot, request.task(), planned, excluded);
            if (result instanceof Placed placed) {
                planned.put(placed.node(), planned.get(placed.node()).with(
                        new RunningService(request.id(), request.task(), true, heapBytes(snapshot, request.task()))));
            }
            results.add(result);
        }
        return List.copyOf(results);
    }

    /**
     * One service to place with {@link #placeAll}.
     *
     * @param id   the service id it gets or had
     * @param task its task
     */
    public record ServiceRequest(ServiceId id, String task) {

        /** Checks the values. */
        public ServiceRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(task, "task");
        }
    }

    /** The nodes a task may run on, in the order of {@code nodes:} or of {@code nodes.yml}. */
    static List<NodeId> candidates(ConfigSnapshot snapshot, String task) {
        TaskConfig config = snapshot.tasks().get(task);
        if (config == null) {
            throw new IllegalArgumentException("unknown task " + task);
        }
        List<String> ids = config.nodes().isEmpty()
                ? snapshot.nodes().nodes().stream().map(NodeConfig::id).toList()
                : config.nodes();
        return ids.stream().distinct().map(NodeId::new).toList();
    }

    /** The maximum heap of one service of the task in bytes. */
    static long heapBytes(ConfigSnapshot snapshot, String task) {
        TaskConfig config = snapshot.tasks().get(task);
        if (config == null) {
            throw new IllegalArgumentException("unknown task " + task);
        }
        GroupConfig group = snapshot.groups().get(config.group());
        if (group == null) {
            throw new IllegalArgumentException("task " + task + " has no group " + config.group());
        }
        return group.heap().max().megabytes() * 1024L * 1024L;
    }
}
