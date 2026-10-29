package net.vectora.cloud.node.scaling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.node.service.ServiceIds;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServicePlanner;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;

/**
 * The pure decisions of the autoscaler, without any I/O or clock of its own: which task gets
 * another service and which dynamic service may go. {@link Autoscaler} feeds it the configuration,
 * the services of the node, the player counts and memory, and what it remembers from earlier
 * rounds. The rules are in {@code docs/cloud/scaling.md}.
 *
 * <p>Per task of this node whose group is wanted ({@link ServicePlanner#isWanted}), at most one
 * decision per task and round, and none before {@code cooldown} has passed since the last one:
 * <ol>
 *   <li><b>min-services</b>: fewer than {@code min-services} count, so one more starts: a static
 *       one while the task has fewer static services than {@code static-services}, otherwise a
 *       dynamic one. The memory budget does not apply here.</li>
 *   <li><b>above max-services</b> (a limit that was lowered): an empty dynamic service stops at
 *       once.</li>
 *   <li><b>load</b>: the load of the group, the players of its running services divided by their
 *       slots ({@code players-per-service} of each service's task), has reached
 *       {@code scale-up-at}, no service of the group is starting, the task may have dynamic
 *       services and {@code max-services} leaves room: one more dynamic service starts, if the
 *       memory budget allows it.</li>
 *   <li><b>low load</b>: the task has more than {@code min-services}, and without its emptiest
 *       running dynamic service the load of the group would stay below {@code scale-down-below};
 *       once that has held for {@code scale-down-after}, that service is emptied
 *       ({@link Drain}: its players are moved to other services of the group) and stopped, or
 *       stopped at once if it has no players.</li>
 * </ol>
 * Static services are never stopped or emptied, proxies never scaled. A load that is not known
 * (a running service of the group without a player count) blocks both scale-up and scale-down.
 */
public final class ScalingPlanner {

    /** Kept free for the operating system when {@code max-memory} is not set: 1 GiB. */
    public static final long OS_RESERVE = 1024L * 1024L * 1024L;

    /** On top of the node's own maximum heap, for its metaspace, threads and buffers: 256 MiB. */
    public static final long NODE_NON_HEAP_RESERVE = 256L * 1024L * 1024L;

    /** For a MariaDB the node runs on this machine (managed or Docker): 512 MiB. */
    public static final long DATABASE_RESERVE = 512L * 1024L * 1024L;

    private ScalingPlanner() {
    }

    /** Why a service starts or stops. */
    public enum Reason {
        /** Fewer services than {@code min-services}. */
        MIN_SERVICES,
        /** The load of the group reached {@code scale-up-at}. */
        LOAD,
        /** With one service fewer the load would stay below {@code scale-down-below}. */
        LOW_LOAD,
        /** More services than {@code max-services}. */
        ABOVE_MAX
    }

    /** Why a task that needs another service does not get one now. */
    public enum Hold {
        /** The monitor has no memory reading of the node yet (or is switched off). */
        MEMORY_UNKNOWN,
        /** The maximum heaps of every service on the node and the new one exceed the budget. */
        MEMORY_BUDGET,
        /** Less memory is free right now than the new service's maximum heap. */
        MEMORY_FREE,
        /** Its last starts failed; see {@link Autoscaler#FAILURE_LIMIT}. */
        PAUSED
    }

    /** One decision of a round. */
    public sealed interface Decision permits Start, Stop, Drain, Held {

        /**
         * The task the decision is about.
         *
         * @return task name
         */
        String task();
    }

    /**
     * Start one more service of a task.
     *
     * @param task   task name
     * @param index  index of a static service, empty for a dynamic one
     * @param reason why
     */
    public record Start(String task, OptionalInt index, Reason reason) implements Decision {
    }

    /**
     * Stop one service that has no players.
     *
     * @param task    task name
     * @param service the service
     * @param reason  why
     */
    public record Stop(String task, ServiceId service, Reason reason) implements Decision {
    }

    /**
     * Move the players of a dynamic service to other services of its group, then stop it; if
     * players are left after the timeout, it keeps running.
     *
     * @param task    task name
     * @param service the service
     * @param reason  why
     */
    public record Drain(String task, ServiceId service, Reason reason) implements Decision {
    }

    /**
     * A task needs another service but does not get one now.
     *
     * @param task task name
     * @param hold why
     */
    public record Held(String task, Hold hold) implements Decision {
    }

    /**
     * Memory of the node.
     *
     * @param used     bytes in use on the machine
     * @param total    bytes of the machine or container
     * @param nodeHeap maximum heap of the node's own JVM, 0 if unknown
     */
    public record Memory(long used, long total, long nodeHeap) {

        /** Without the node's heap, for tests. */
        public Memory(long used, long total) {
            this(used, total, 0);
        }
    }

    /**
     * What the services report.
     *
     * @param players players per service; a service without an entry has no known count
     * @param memory  memory of the node, if measured
     */
    public record Signals(Map<ServiceId, Integer> players, Optional<Memory> memory) {

        /** Copies the values. */
        public Signals {
            players = Map.copyOf(players);
            Objects.requireNonNull(memory, "memory");
        }

        /** No player count and no memory reading. */
        public static Signals none() {
            return new Signals(Map.of(), Optional.empty());
        }

        OptionalInt playersOf(ServiceId id) {
            Integer value = players.get(id);
            return value == null || value < 0 ? OptionalInt.empty() : OptionalInt.of(value);
        }
    }

    /**
     * Everything one round decides on.
     *
     * @param snapshot    the configuration
     * @param node        id of this node
     * @param services    every service the node knows
     * @param signals     player counts and memory
     * @param lastAction  per task, when the last decision was carried out
     * @param pausedUntil per task, until when no service is started after failed starts
     * @param lowSince    per task, since when its low-load condition has held without a break
     * @param released    static services that stopped because their group was switched off
     * @param draining    services whose players are being moved away
     * @param now         the time of the round
     */
    public record Input(ConfigSnapshot snapshot, String node, List<ServiceInfo> services, Signals signals,
                        Map<String, Instant> lastAction, Map<String, Instant> pausedUntil,
                        Map<String, Instant> lowSince, Set<ServiceId> released, Set<ServiceId> draining,
                        Instant now) {

        /** Copies the values. */
        public Input {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(node, "node");
            services = List.copyOf(services);
            Objects.requireNonNull(signals, "signals");
            lastAction = Map.copyOf(lastAction);
            pausedUntil = Map.copyOf(pausedUntil);
            lowSince = Map.copyOf(lowSince);
            released = Set.copyOf(released);
            draining = Set.copyOf(draining);
            Objects.requireNonNull(now, "now");
        }
    }

    /**
     * Players and slots of the running services of a group.
     *
     * @param players players on them
     * @param slots   the sum of their {@code players-per-service}
     */
    public record Load(long players, long slots) {

        /** {@code players / slots >= percent / 100}, without rounding. */
        boolean reaches(int percent) {
            return slots > 0 && players * 100 >= slots * percent;
        }
    }

    /**
     * Decides one round.
     *
     * @param in what the round sees
     * @return the decisions, in task name order, at most one per task
     */
    public static List<Decision> plan(Input in) {
        List<Decision> decisions = new ArrayList<>();
        for (Map.Entry<String, TaskConfig> entry : in.snapshot().tasks().entrySet()) {
            String name = entry.getKey();
            TaskConfig task = entry.getValue();
            GroupConfig group = in.snapshot().groups().get(task.group());
            if (group == null || task.scaling() == null || !ServicePlanner.isWanted(group, in.snapshot().cloud())
                    || !ServicePlanner.runsOn(task, in.node())) {
                continue;
            }
            Instant last = in.lastAction().get(name);
            if (last != null && in.now().isBefore(last.plus(task.scaling().cooldown().duration()))) {
                continue;
            }
            decide(in, name, task, group).ifPresent(decisions::add);
        }
        return List.copyOf(decisions);
    }

    private static Optional<Decision> decide(Input in, String name, TaskConfig task, GroupConfig group) {
        ScalingConfig scaling = task.scaling();
        List<ServiceInfo> mine = in.services().stream().filter(s -> name.equals(s.task())).toList();
        long counted = mine.stream().filter(s -> counts(s, in.released())).count();
        long occupying = mine.stream().filter(ScalingPlanner::occupiesASlot).count();
        Instant paused = in.pausedUntil().get(name);
        boolean isPaused = paused != null && in.now().isBefore(paused);

        if (counted < scaling.minServices()) {
            if (occupying >= scaling.maxServices()) {
                return Optional.empty();
            }
            if (isPaused) {
                return Optional.of(new Held(name, Hold.PAUSED));
            }
            OptionalInt index = releasedIndex(in, name);
            if (index.isEmpty() && staticCount(mine) < scaling.staticServices()) {
                index = staticIndex(in, task);
                if (index.isEmpty()) {
                    return Optional.empty();
                }
            } else if (index.isEmpty() && !scaling.allowsDynamic()) {
                return Optional.empty();
            }
            return Optional.of(new Start(name, index, Reason.MIN_SERVICES));
        }
        if (group.software() == ServerSoftware.VELOCITY) {
            return Optional.empty();
        }
        if (counted > scaling.maxServices()) {
            return mine.stream()
                    .filter(s -> s.state() == ServiceState.RUNNING && s.mode() == TaskMode.DYNAMIC
                            && !in.draining().contains(s.id())
                            && in.signals().playersOf(s.id()).orElse(-1) == 0)
                    .max(Comparator.comparingInt(ServiceInfo::index))
                    .map(s -> new Stop(name, s.id(), Reason.ABOVE_MAX));
        }
        if (!scaling.allowsDynamic()) {
            return Optional.empty();
        }

        Optional<Load> load = groupLoad(in.snapshot(), task.group(), in.services(), in.signals(), in.draining(), null);
        boolean starting = in.services().stream().anyMatch(s -> task.group().equals(s.group())
                && (s.state() == ServiceState.PREPARED || s.state() == ServiceState.STARTING));
        if (!starting && load.isPresent() && occupying < scaling.maxServices() && load.get().reaches(scaling.scaleUpAt())) {
            if (isPaused) {
                return Optional.of(new Held(name, Hold.PAUSED));
            }
            Optional<Hold> memory = memoryHold(in, group);
            return Optional.of(memory.<Decision>map(hold -> new Held(name, hold))
                    .orElseGet(() -> new Start(name, OptionalInt.empty(), Reason.LOAD)));
        }

        Instant since = in.lowSince().get(name);
        if (since != null && !in.now().isBefore(since.plus(scaling.scaleDownAfter().duration()))) {
            Optional<ServiceInfo> candidate = scaleDownCandidate(in.snapshot(), name, in.services(), in.signals(),
                    in.released(), in.draining());
            if (candidate.isPresent()) {
                ServiceId id = candidate.get().id();
                return Optional.of(in.signals().playersOf(id).orElse(-1) == 0
                        ? new Stop(name, id, Reason.LOW_LOAD)
                        : new Drain(name, id, Reason.LOW_LOAD));
            }
        }
        return Optional.empty();
    }

    /**
     * The dynamic service of a task that may go now because the load is low, without looking at
     * {@code scale-down-after}: the task has more than {@code min-services}, and without its
     * emptiest running dynamic service (of equal ones the highest index) the load of the group
     * would stay below {@code scale-down-below}, with at least one other running service left. The
     * autoscaler calls it every round to know since when the condition holds.
     *
     * @param snapshot the configuration
     * @param task     task name
     * @param services every service of the node
     * @param signals  player counts
     * @param released static services released by a switch
     * @param draining services being emptied
     * @return the service, or empty if none may go
     */
    public static Optional<ServiceInfo> scaleDownCandidate(ConfigSnapshot snapshot, String task,
                                                           List<ServiceInfo> services, Signals signals,
                                                           Set<ServiceId> released, Set<ServiceId> draining) {
        TaskConfig config = snapshot.tasks().get(task);
        if (config == null || config.scaling() == null) {
            return Optional.empty();
        }
        GroupConfig group = snapshot.groups().get(config.group());
        if (group == null || group.software() == ServerSoftware.VELOCITY) {
            return Optional.empty();
        }
        ScalingConfig scaling = config.scaling();
        long counted = services.stream().filter(s -> task.equals(s.task()) && counts(s, released)).count();
        if (counted <= scaling.minServices()) {
            return Optional.empty();
        }
        Optional<ServiceInfo> candidate = services.stream()
                .filter(s -> task.equals(s.task()) && s.state() == ServiceState.RUNNING
                        && s.mode() == TaskMode.DYNAMIC && !draining.contains(s.id())
                        && signals.playersOf(s.id()).isPresent())
                .min(Comparator.<ServiceInfo>comparingInt(s -> signals.playersOf(s.id()).getAsInt())
                        .thenComparing(Comparator.comparingInt(ServiceInfo::index).reversed()));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Optional<Load> without = groupLoad(snapshot, config.group(), services, signals, draining, candidate.get().id());
        if (without.isEmpty() || without.get().slots() <= 0) {
            return Optional.empty();
        }
        // Every player of the group, the candidate's included: they move to the services that stay.
        long players = without.get().players() + signals.playersOf(candidate.get().id()).getAsInt();
        return players * 100 < without.get().slots() * (long) scaling.scaleDownBelow() ? candidate : Optional.empty();
    }

    /**
     * The load of a group: players and slots of its running services, without those being emptied
     * and without {@code leaveOut}. Empty if a running service has no known player count or a
     * service belongs to no task any more.
     *
     * @param snapshot the configuration
     * @param group    the group
     * @param services every service of the node
     * @param signals  player counts
     * @param draining services being emptied
     * @param leaveOut a service to count as gone, or {@code null}
     * @return the load
     */
    public static Optional<Load> groupLoad(ConfigSnapshot snapshot, String group, List<ServiceInfo> services,
                                           Signals signals, Set<ServiceId> draining, ServiceId leaveOut) {
        long players = 0;
        long slots = 0;
        for (ServiceInfo s : services) {
            if (!group.equals(s.group()) || s.state() != ServiceState.RUNNING || draining.contains(s.id())) {
                continue;
            }
            TaskConfig task = snapshot.tasks().get(s.task());
            OptionalInt count = signals.playersOf(s.id());
            if (task == null || task.scaling() == null || count.isEmpty()) {
                return Optional.empty();
            }
            if (s.id().equals(leaveOut)) {
                continue;
            }
            players += count.getAsInt();
            slots += task.scaling().playersPerService();
        }
        return Optional.of(new Load(players, slots));
    }

    /** Counts toward {@code min-services}. */
    private static boolean counts(ServiceInfo s, Set<ServiceId> released) {
        if (s.mode() == TaskMode.STATIC) {
            // A released one still stopping counts until it has stopped, so it comes back at its own
            // index and no second static service is started next to it.
            return !released.contains(s.id()) || s.state() != ServiceState.STOPPED;
        }
        return switch (s.state()) {
            case PREPARED, STARTING, RUNNING, CRASHED -> true;
            case STOPPING, STOPPED -> false;
        };
    }

    /**
     * Counts toward {@code max-services} as the service core counts it: everything with a process or
     * about to get one. A crashed service may be waiting for its restart.
     */
    private static boolean occupiesASlot(ServiceInfo s) {
        return s.state() != ServiceState.STOPPED;
    }

    /** The lowest static service of the task that stopped because its group was switched off. */
    private static OptionalInt releasedIndex(Input in, String name) {
        return in.services().stream()
                .filter(s -> name.equals(s.task()) && s.mode() == TaskMode.STATIC && in.released().contains(s.id())
                        && !occupiesASlot(s))
                .mapToInt(ServiceInfo::index)
                .min();
    }

    /** Static services of the task, in any state: they keep their folder and always count. */
    private static long staticCount(List<ServiceInfo> mine) {
        return mine.stream().filter(s -> s.mode() == TaskMode.STATIC).count();
    }

    /**
     * The index of a new static service: the lowest index of the group without any service, so no
     * folder of a server the operator stopped by hand is used.
     */
    private static OptionalInt staticIndex(Input in, TaskConfig task) {
        Set<Integer> taken = new HashSet<>();
        for (ServiceInfo s : in.services()) {
            if (task.group().equals(s.group())) {
                taken.add(s.index());
            }
        }
        for (int index = 1; index <= ServiceIds.MAX_INDEX; index++) {
            if (!taken.contains(index)) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The memory the services of this node may use: {@code max-memory} of the node, at most the
     * measured memory; without it, the measured memory minus what the system, the node itself and
     * a database server it runs need ({@link #reserve}).
     *
     * @param snapshot the configuration
     * @param node     id of this node
     * @param memory   the measured memory
     * @return bytes, 0 or more
     */
    public static long budget(ConfigSnapshot snapshot, String node, Memory memory) {
        Optional<NodeConfig> config = snapshot.nodes().find(node);
        if (config.isPresent() && config.get().maxMemory() != null) {
            return Math.min(config.get().maxMemory().megabytes() * 1024L * 1024L, memory.total());
        }
        return Math.max(0, memory.total() - reserve(snapshot, memory.nodeHeap()));
    }

    /**
     * What stays free when {@code max-memory} is not set: {@link #OS_RESERVE}, the node's maximum
     * heap plus {@link #NODE_NON_HEAP_RESERVE}, and {@link #DATABASE_RESERVE} if the database
     * server is wanted and runs on this machine (managed or Docker).
     *
     * @param snapshot the configuration
     * @param nodeHeap maximum heap of the node's JVM
     * @return bytes
     */
    public static long reserve(ConfigSnapshot snapshot, long nodeHeap) {
        long reserve = OS_RESERVE + Math.max(0, nodeHeap) + NODE_NON_HEAP_RESERVE;
        var cloud = snapshot.cloud();
        if (cloud.servers() != null && cloud.servers().database() && cloud.database() != null
                && (cloud.database().mode() == DatabaseMode.MANAGED || cloud.database().mode() == DatabaseMode.DOCKER)) {
            reserve += DATABASE_RESERVE;
        }
        return reserve;
    }

    /**
     * The memory budget of the node for one more service of {@code group}: the maximum heaps of
     * every service with a process (or about to get one) plus the new one must fit into
     * {@link #budget}, and the new maximum heap must be free right now.
     */
    private static Optional<Hold> memoryHold(Input in, GroupConfig group) {
        if (in.signals().memory().isEmpty()) {
            return Optional.of(Hold.MEMORY_UNKNOWN);
        }
        Memory memory = in.signals().memory().get();
        long heap = heapBytes(group);
        long declared = heap;
        for (ServiceInfo s : in.services()) {
            if (s.state() == ServiceState.PREPARED || s.state().hasProcess()) {
                GroupConfig of = in.snapshot().groups().get(s.group());
                declared += of == null ? 0 : heapBytes(of);
            }
        }
        if (declared > budget(in.snapshot(), in.node(), memory)) {
            return Optional.of(Hold.MEMORY_BUDGET);
        }
        if (memory.total() - memory.used() < heap) {
            return Optional.of(Hold.MEMORY_FREE);
        }
        return Optional.empty();
    }

    /**
     * The maximum heap of one service of a group in bytes.
     *
     * @param group the group
     * @return {@code heap.max} in bytes
     */
    static long heapBytes(GroupConfig group) {
        return group.heap().max().megabytes() * 1024L * 1024L;
    }
}
