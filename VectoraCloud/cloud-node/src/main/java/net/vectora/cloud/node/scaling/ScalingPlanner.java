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
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.MemoryReserve;
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
 * more services and which dynamic service may go. {@link Autoscaler} feeds it the configuration,
 * the services of the node, the player counts and memory, and what it remembers from earlier
 * rounds. The rules are in {@code docs/cloud/scaling.md}.
 *
 * <p>Per task of this node whose group is wanted ({@link ServicePlanner#isWanted}):
 * <ol>
 *   <li><b>min-services</b>: fewer than {@code min-services} count, so every missing one starts in
 *       this round, without waiting for {@code cooldown}: static ones while the task has fewer
 *       static services than {@code static-services}, then dynamic ones. The memory budget does not
 *       apply here.</li>
 *   <li><b>above max-services</b> (a limit that was lowered): an empty dynamic service is emptied
 *       ({@link Drain}) and stopped.</li>
 *   <li><b>load</b>: the load of the group, the players of its running services divided by their
 *       slots ({@code players-per-service} of each service's task), has reached
 *       {@code scale-up-at}, no service of the group is starting and none was started for the group
 *       earlier in this round, the task may have dynamic services and {@code max-services} leaves
 *       room: one more dynamic service starts, if the memory budget allows it.</li>
 *   <li><b>low load</b>: the task has more than {@code min-services}, and without its emptiest
 *       running dynamic service the load of the group would stay below {@code scale-down-below};
 *       once that has held for {@code scale-down-after}, that service is emptied ({@link Drain})
 *       and stopped.</li>
 * </ol>
 * Except for min-services, at most one decision per task and round, and none before
 * {@code cooldown} has passed since the last one. Every scale-down goes through {@link Drain}: the
 * proxies stop sending players there first, so a player routed to an empty server in the meantime
 * is not lost. Static services are never emptied or stopped, proxies never scaled. A static
 * service above {@code static-services} (the number was lowered) counts as dynamic: of the static
 * services of a task the ones with the lowest indices stay static. A load that is not known (a
 * running service of the group without a player count) blocks both scale-up and scale-down.
 */
public final class ScalingPlanner {

    private ScalingPlanner() {
    }

    /** Why a service starts or goes. */
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
    public sealed interface Decision permits Start, Drain, Held {

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
     * Close a dynamic service on the proxies, move its players to other services of its group and
     * stop it once it is empty; if players are left after the drain timeout, it keeps running.
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
     * @param nodeHeap maximum heap of the node's JVM, 0 if unknown
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
     * @param players players per service for the load: the lower of the bridge's and a watching
     *                proxy's count; a service without an entry has no known count
     * @param present players per service for "is it empty": the higher of the two counts; a
     *                service without an entry falls back to {@code players}
     * @param memory  memory of the node, if measured
     */
    public record Signals(Map<ServiceId, Integer> players, Map<ServiceId, Integer> present,
                          Optional<Memory> memory) {

        /** Copies the values. */
        public Signals {
            players = Map.copyOf(players);
            present = Map.copyOf(present);
            Objects.requireNonNull(memory, "memory");
        }

        /** One count per service for both uses. */
        public Signals(Map<ServiceId, Integer> players, Optional<Memory> memory) {
            this(players, Map.of(), memory);
        }

        /** No player count and no memory reading. */
        public static Signals none() {
            return new Signals(Map.of(), Optional.empty());
        }

        OptionalInt playersOf(ServiceId id) {
            Integer value = players.get(id);
            return value == null || value < 0 ? OptionalInt.empty() : OptionalInt.of(value);
        }

        /** The higher count, to judge whether nobody is on the service. */
        OptionalInt presentOn(ServiceId id) {
            OptionalInt low = playersOf(id);
            Integer high = present.get(id);
            if (low.isEmpty()) {
                return low;
            }
            return high == null || high < 0 ? low : OptionalInt.of(Math.max(low.getAsInt(), high));
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
     * @return the decisions, in task name order; several starts for a task below
     *         {@code min-services}, otherwise at most one per task
     */
    public static List<Decision> plan(Input in) {
        List<Decision> decisions = new ArrayList<>();
        // Groups that get a service by load in this round: a second task of the group waits for it.
        Set<String> grownThisRound = new HashSet<>();
        for (Map.Entry<String, TaskConfig> entry : in.snapshot().tasks().entrySet()) {
            String name = entry.getKey();
            TaskConfig task = entry.getValue();
            GroupConfig group = in.snapshot().groups().get(task.group());
            if (group == null || task.scaling() == null || !ServicePlanner.isWanted(group, in.snapshot().cloud())
                    || !ServicePlanner.runsOn(task, in.node())) {
                continue;
            }
            List<Decision> filling = fillToMinimum(in, name, task);
            if (filling != null) {
                decisions.addAll(filling);
                continue;
            }
            Instant last = in.lastAction().get(name);
            if (last != null && in.now().isBefore(last.plus(task.scaling().cooldown().duration()))) {
                continue;
            }
            Optional<Decision> decision = decide(in, name, task, group, grownThisRound.contains(task.group()));
            decision.ifPresent(decisions::add);
            if (decision.isPresent() && decision.get() instanceof Start) {
                grownThisRound.add(task.group());
            }
        }
        return List.copyOf(decisions);
    }

    /**
     * The starts that bring a task up to {@code min-services}, all in this round and without
     * {@code cooldown}; {@code null} if the task has its minimum, so the other rules apply.
     */
    private static List<Decision> fillToMinimum(Input in, String name, TaskConfig task) {
        ScalingConfig scaling = task.scaling();
        List<ServiceInfo> mine = in.services().stream().filter(s -> name.equals(s.task())).toList();
        Set<ServiceId> demoted = demoted(mine, scaling);
        long counted = mine.stream().filter(s -> counts(s, in.released(), demoted)).count();
        if (counted >= scaling.minServices()) {
            return null;
        }
        long occupying = mine.stream().filter(ScalingPlanner::occupiesASlot).count();
        if (occupying >= scaling.maxServices()) {
            return List.of();
        }
        Instant paused = in.pausedUntil().get(name);
        if (paused != null && in.now().isBefore(paused)) {
            return List.of(new Held(name, Hold.PAUSED));
        }
        List<Decision> starts = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (ServiceInfo s : in.services()) {
            if (task.group().equals(s.group())) {
                taken.add(s.index());
            }
        }
        List<Integer> released = in.services().stream()
                .filter(s -> name.equals(s.task()) && s.mode() == TaskMode.STATIC && !demoted.contains(s.id())
                        && in.released().contains(s.id()) && !occupiesASlot(s))
                .map(ServiceInfo::index).sorted().toList();
        long statics = Math.min(staticCount(mine), scaling.staticServices());
        int nextReleased = 0;
        for (long missing = scaling.minServices() - counted; missing > 0; missing--) {
            if (occupying >= scaling.maxServices()) {
                break;
            }
            OptionalInt index;
            if (nextReleased < released.size()) {
                index = OptionalInt.of(released.get(nextReleased++));
            } else if (statics < scaling.staticServices()) {
                index = freeIndex(taken);
                if (index.isEmpty()) {
                    break;
                }
                taken.add(index.getAsInt());
                statics++;
            } else if (scaling.allowsDynamic()) {
                index = OptionalInt.empty();
            } else {
                break;
            }
            starts.add(new Start(name, index, Reason.MIN_SERVICES));
            occupying++;
        }
        return starts;
    }

    private static Optional<Decision> decide(Input in, String name, TaskConfig task, GroupConfig group,
                                             boolean groupGrown) {
        ScalingConfig scaling = task.scaling();
        if (group.software() == ServerSoftware.VELOCITY) {
            return Optional.empty();
        }
        List<ServiceInfo> mine = in.services().stream().filter(s -> name.equals(s.task())).toList();
        Set<ServiceId> demoted = demoted(mine, scaling);
        long counted = mine.stream().filter(s -> counts(s, in.released(), demoted)).count();
        long occupying = mine.stream().filter(ScalingPlanner::occupiesASlot).count();
        Instant paused = in.pausedUntil().get(name);
        boolean isPaused = paused != null && in.now().isBefore(paused);

        if (counted > scaling.maxServices()) {
            return mine.stream()
                    .filter(s -> s.state() == ServiceState.RUNNING && isDynamic(s, demoted)
                            && !in.draining().contains(s.id())
                            && in.signals().presentOn(s.id()).orElse(-1) == 0)
                    .max(Comparator.comparingInt(ServiceInfo::index))
                    .map(s -> new Drain(name, s.id(), Reason.ABOVE_MAX));
        }

        Optional<Load> load = groupLoad(in.snapshot(), task.group(), in.services(), in.signals(), in.draining(), null);
        boolean starting = groupGrown || in.services().stream().anyMatch(s -> task.group().equals(s.group())
                && (s.state() == ServiceState.PREPARED || s.state() == ServiceState.STARTING));
        if (scaling.allowsDynamic() && !starting && load.isPresent() && occupying < scaling.maxServices()
                && load.get().reaches(scaling.scaleUpAt())) {
            if (isPaused) {
                return Optional.of(new Held(name, Hold.PAUSED));
            }
            Optional<Hold> memory = memoryHold(in, group);
            return Optional.of(memory.<Decision>map(hold -> new Held(name, hold))
                    .orElseGet(() -> new Start(name, OptionalInt.empty(), Reason.LOAD)));
        }

        Instant since = in.lowSince().get(name);
        if (since != null && !in.now().isBefore(since.plus(scaling.scaleDownAfter().duration()))) {
            return scaleDownCandidate(in.snapshot(), name, in.services(), in.signals(), in.released(), in.draining())
                    .map(candidate -> new Drain(name, candidate.id(), Reason.LOW_LOAD));
        }
        return Optional.empty();
    }

    /**
     * The dynamic service of a task that may go now because the load is low, without looking at
     * {@code scale-down-after}: the task has more than {@code min-services}, and without its
     * emptiest running dynamic service (of equal ones the highest index) the load of the group
     * would stay below {@code scale-down-below}, with at least one other running service left. The
     * players of the candidate are counted with the higher of the bridge's and the proxy's count.
     * The autoscaler calls it every round to know since when the condition holds.
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
        List<ServiceInfo> mine = services.stream().filter(s -> task.equals(s.task())).toList();
        Set<ServiceId> demoted = demoted(mine, scaling);
        long counted = mine.stream().filter(s -> counts(s, released, demoted)).count();
        if (counted <= scaling.minServices()) {
            return Optional.empty();
        }
        Optional<ServiceInfo> candidate = mine.stream()
                .filter(s -> s.state() == ServiceState.RUNNING && isDynamic(s, demoted)
                        && !draining.contains(s.id()) && signals.presentOn(s.id()).isPresent())
                .min(Comparator.<ServiceInfo>comparingInt(s -> signals.presentOn(s.id()).getAsInt())
                        .thenComparing(Comparator.comparingInt(ServiceInfo::index).reversed()));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Optional<Load> without = groupLoad(snapshot, config.group(), services, signals, draining, candidate.get().id());
        if (without.isEmpty() || without.get().slots() <= 0) {
            return Optional.empty();
        }
        // Every player of the group, the candidate's included: they move to the services that stay.
        long players = without.get().players() + signals.presentOn(candidate.get().id()).getAsInt();
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

    /**
     * The static services of a task above {@code static-services}: after the number was lowered,
     * the ones with the highest indices count as dynamic, so they can be emptied and stopped. They
     * keep their folder.
     *
     * @param mine    the services of the task
     * @param scaling its scaling
     * @return the static services that count as dynamic
     */
    static Set<ServiceId> demoted(List<ServiceInfo> mine, ScalingConfig scaling) {
        List<ServiceInfo> statics = mine.stream().filter(s -> s.mode() == TaskMode.STATIC)
                .sorted(Comparator.comparingInt(ServiceInfo::index)).toList();
        Set<ServiceId> result = new HashSet<>();
        for (int i = Math.max(0, scaling.staticServices()); i < statics.size(); i++) {
            result.add(statics.get(i).id());
        }
        return result;
    }

    private static boolean isDynamic(ServiceInfo s, Set<ServiceId> demoted) {
        return s.mode() == TaskMode.DYNAMIC || demoted.contains(s.id());
    }

    /** Counts toward {@code min-services}. */
    private static boolean counts(ServiceInfo s, Set<ServiceId> released, Set<ServiceId> demoted) {
        if (!isDynamic(s, demoted)) {
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

    /** Static services of the task, in any state: they keep their folder and always count. */
    private static long staticCount(List<ServiceInfo> mine) {
        return mine.stream().filter(s -> s.mode() == TaskMode.STATIC).count();
    }

    /**
     * The index of a new static service: the lowest index of the group without any service, so no
     * folder of a server the operator stopped by hand is used.
     */
    private static OptionalInt freeIndex(Set<Integer> taken) {
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
        return Math.max(0, memory.total() - reserve(snapshot, node, memory.nodeHeap()));
    }

    /**
     * What stays free when {@code max-memory} is not set: the node's {@code memory-reserve} in
     * {@code nodes.yml} ({@code system} and {@code node-overhead}, plus {@code database} while
     * {@link MemoryReserve#countsDatabase} holds; missing fields and an unknown node at their
     * defaults) and the node's own maximum heap.
     *
     * @param snapshot the configuration
     * @param node     id of this node
     * @param nodeHeap maximum heap of the node's JVM
     * @return bytes
     */
    public static long reserve(ConfigSnapshot snapshot, String node, long nodeHeap) {
        MemoryReserve reserve = snapshot.nodes().find(node).map(NodeConfig::memoryReserve)
                .orElseGet(MemoryReserve::defaults);
        long megabytes = reserve.megabytes(MemoryReserve.countsDatabase(snapshot.cloud()));
        return megabytes * 1024L * 1024L + Math.max(0, nodeHeap);
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
