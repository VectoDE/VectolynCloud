package net.vectora.cloud.node.scaling;

import java.time.Duration;
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
import net.vectora.cloud.config.model.GroupConfig;
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
 * another service and which empty service may go. {@link Autoscaler} feeds it the configuration,
 * the services of the node, the player counts and memory of the monitor, and what it remembers
 * from earlier rounds.
 *
 * <p>Rules, per task of this node whose group is wanted ({@link ServicePlanner#isWanted}, that is
 * {@code GroupRules.isWanted} of cloud-config), at most one decision per task and round, and none
 * before {@code cooldown} has passed since the last one:
 * <ol>
 *   <li><b>min-services</b>: fewer than {@code min-services} count, so one more starts. A dynamic
 *       service counts while it is prepared, starting, running or waiting for its restart after a
 *       crash. A static service always counts, also stopped or given up after its crashes, so a
 *       server the operator stopped by hand stays down; only one that stopped because its group
 *       was switched off ({@code released}) comes back, at its own index. The memory budget does
 *       not apply here, as on the start of the node.</li>
 *   <li><b>above max-services</b> (a dynamic task whose limit was lowered): an empty dynamic
 *       service stops, without waiting for {@code scale-down-after}.</li>
 *   <li><b>players</b>: every running service of a dynamic task has reached {@code scale-up-at}
 *       percent of {@code players-per-service}, none is still starting, and {@code max-services}
 *       leaves room: one more starts, if the memory budget of the node allows it.</li>
 *   <li><b>empty</b>: a dynamic service has had no players for {@code scale-down-after}, the task
 *       has more than {@code min-services}, and another service of the task has room below the
 *       threshold (hysteresis: stopping the last one with room would start a new one at once).</li>
 * </ol>
 * Static services are never stopped, proxies never scaled up or down. A player count that is not
 * known (no report yet, an outdated one, the monitor switched off) never counts as full and never
 * as empty.
 */
public final class ScalingPlanner {

    private ScalingPlanner() {
    }

    /** Why a service starts or stops. */
    public enum Reason {
        /** Fewer services than {@code min-services}. */
        MIN_SERVICES,
        /** Every running service reached the threshold. */
        PLAYERS,
        /** Empty for {@code scale-down-after} while another service has room. */
        EMPTY,
        /** More services than {@code max-services}. */
        ABOVE_MAX
    }

    /** Why a task that needs another service does not get one now. */
    public enum Hold {
        /** The monitor has no memory reading of the node yet (or is switched off). */
        MEMORY_UNKNOWN,
        /** The maximum heaps of every service on the node and the new one exceed its memory. */
        MEMORY_BUDGET,
        /** Less memory is free right now than the new service's maximum heap. */
        MEMORY_FREE,
        /** Its last starts failed; see {@link Autoscaler#FAILURE_LIMIT}. */
        PAUSED
    }

    /** One decision of a round. */
    public sealed interface Decision permits Start, Stop, Held {

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
     * @param index  index of a static service, empty for a dynamic one (the service core takes the
     *               lowest free index of the group)
     * @param reason why
     */
    public record Start(String task, OptionalInt index, Reason reason) implements Decision {
    }

    /**
     * Stop one service.
     *
     * @param task    task name
     * @param service the service
     * @param reason  why
     */
    public record Stop(String task, ServiceId service, Reason reason) implements Decision {
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
     * Memory of the node, from the monitor.
     *
     * @param used  bytes in use
     * @param total bytes of the machine or container
     */
    public record Memory(long used, long total) {
    }

    /**
     * What the services report.
     *
     * @param players players online per service; a service without an entry has no known count
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
     * @param emptySince  per running service, since when it has had no players
     * @param released    static services that stopped because their group was switched off
     * @param now         the time of the round
     */
    public record Input(ConfigSnapshot snapshot, String node, List<ServiceInfo> services, Signals signals,
                        Map<String, Instant> lastAction, Map<String, Instant> pausedUntil,
                        Map<ServiceId, Instant> emptySince, Set<ServiceId> released, Instant now) {

        /** Copies the values. */
        public Input {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(node, "node");
            services = List.copyOf(services);
            Objects.requireNonNull(signals, "signals");
            lastAction = Map.copyOf(lastAction);
            pausedUntil = Map.copyOf(pausedUntil);
            emptySince = Map.copyOf(emptySince);
            released = Set.copyOf(released);
            Objects.requireNonNull(now, "now");
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
            OptionalInt index = task.mode() == TaskMode.STATIC ? staticIndex(in, name, task) : OptionalInt.empty();
            if (task.mode() == TaskMode.STATIC && index.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Start(name, index, Reason.MIN_SERVICES));
        }
        if (task.mode() != TaskMode.DYNAMIC || group.software() == ServerSoftware.VELOCITY) {
            return Optional.empty();
        }
        List<ServiceInfo> running = mine.stream().filter(s -> s.state() == ServiceState.RUNNING).toList();
        List<ServiceInfo> removable = running.stream()
                .filter(s -> s.mode() == TaskMode.DYNAMIC && isEmpty(in, s))
                .sorted(Comparator.comparingInt(ServiceInfo::index).reversed())
                .toList();

        if (counted > scaling.maxServices()) {
            return removable.stream().findFirst()
                    .map(s -> (Decision) new Stop(name, s.id(), Reason.ABOVE_MAX));
        }

        boolean starting = mine.stream().anyMatch(s -> s.state() == ServiceState.PREPARED
                || s.state() == ServiceState.STARTING);
        if (!starting && !running.isEmpty() && occupying < scaling.maxServices()
                && running.stream().allMatch(s -> isFull(in, s, scaling))) {
            if (isPaused) {
                return Optional.of(new Held(name, Hold.PAUSED));
            }
            Optional<Hold> memory = memoryHold(in, group);
            return Optional.of(memory.<Decision>map(hold -> new Held(name, hold))
                    .orElseGet(() -> new Start(name, OptionalInt.empty(), Reason.PLAYERS)));
        }

        if (counted > scaling.minServices()) {
            Duration wait = scaling.scaleDownAfter().duration();
            for (ServiceInfo candidate : removable) {
                Instant since = in.emptySince().get(candidate.id());
                if (since == null || in.now().isBefore(since.plus(wait))) {
                    continue;
                }
                boolean roomElsewhere = mine.stream().anyMatch(other -> !other.id().equals(candidate.id())
                        && (other.state() == ServiceState.PREPARED || other.state() == ServiceState.STARTING
                        || other.state() == ServiceState.RUNNING && hasRoom(in, other, scaling)));
                if (roomElsewhere) {
                    return Optional.of(new Stop(name, candidate.id(), Reason.EMPTY));
                }
            }
        }
        return Optional.empty();
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

    /**
     * The index of a new static service: the lowest one that stopped because its group was switched
     * off, otherwise the lowest index of the group without any service, so no folder of a server
     * the operator stopped by hand is used.
     */
    private static OptionalInt staticIndex(Input in, String name, TaskConfig task) {
        OptionalInt released = in.services().stream()
                .filter(s -> name.equals(s.task()) && s.mode() == TaskMode.STATIC && in.released().contains(s.id())
                        && !occupiesASlot(s))
                .mapToInt(ServiceInfo::index)
                .min();
        if (released.isPresent()) {
            return released;
        }
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

    private static boolean isFull(Input in, ServiceInfo s, ScalingConfig scaling) {
        OptionalInt players = in.signals().playersOf(s.id());
        return players.isPresent() && reachesThreshold(players.getAsInt(), scaling);
    }

    private static boolean hasRoom(Input in, ServiceInfo s, ScalingConfig scaling) {
        OptionalInt players = in.signals().playersOf(s.id());
        return players.isPresent() && !reachesThreshold(players.getAsInt(), scaling);
    }

    private static boolean isEmpty(Input in, ServiceInfo s) {
        OptionalInt players = in.signals().playersOf(s.id());
        return players.isPresent() && players.getAsInt() == 0;
    }

    /** {@code players >= players-per-service * scale-up-at / 100}, without rounding. */
    static boolean reachesThreshold(int players, ScalingConfig scaling) {
        return (long) players * 100 >= (long) scaling.playersPerService() * scaling.scaleUpAt();
    }

    /**
     * The memory budget of the node for one more service of {@code group}: the maximum heaps of
     * every service with a process (or about to get one) plus the new one must fit into the memory
     * of the machine, and the new maximum heap must be free right now.
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
        if (declared > memory.total()) {
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
