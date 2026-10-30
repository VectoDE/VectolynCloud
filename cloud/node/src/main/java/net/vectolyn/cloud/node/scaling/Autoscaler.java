package net.vectolyn.cloud.node.scaling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.RestartConfig;
import net.vectolyn.cloud.config.model.ScalingConfig;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.node.channel.ChannelBridge.DrainResult;
import net.vectolyn.cloud.node.scaling.ScalingPlanner.Decision;
import net.vectolyn.cloud.node.scaling.ScalingPlanner.Drain;
import net.vectolyn.cloud.node.scaling.ScalingPlanner.Held;
import net.vectolyn.cloud.node.scaling.ScalingPlanner.Hold;
import net.vectolyn.cloud.node.scaling.ScalingPlanner.Start;
import net.vectolyn.cloud.node.service.ServiceException;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceListener;
import net.vectolyn.cloud.node.service.ServicePlanner;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;

/**
 * Scales the tasks of this node: every {@link #INTERVAL} it reads the configuration, the services
 * and the {@link ScalingSignals}, lets {@link ScalingPlanner} decide, and carries the decisions out
 * through the service core. The configuration is the one of {@code ConfigService}, so a change by
 * hand, on the console or through the API takes effect in the next round, the same way.
 *
 * <p>What it remembers between rounds:
 * <ul>
 *   <li>when each task last got a decision ({@code cooldown});</li>
 *   <li>since when each task could do with one dynamic service fewer ({@code scale-down-after});</li>
 *   <li>which services are being emptied right now ({@link Drain});</li>
 *   <li>static services that stopped because their group was switched off, or {@code eula-accepted}
 *       or {@code setup-complete} was taken back: they come back once the switch is on again. A
 *       service stopped by hand stays down;</li>
 *   <li>failed starts: a start that fails, or a service of the task that crashes, counts. After
 *       {@value #FAILURE_LIMIT} in a row without a service of the task that ran for five minutes,
 *       the task gets no new service for {@link #FAILURE_PAUSE}, or until its task or group file
 *       changes. The restarts after a crash ({@code process.restart}) are not affected.</li>
 * </ul>
 * Nothing happens before the setup is complete and the EULA accepted, while {@link #suspend} is on
 * (the {@code setup} command saves in steps), and after {@link #close()}.
 *
 * <p>Starts and stops run on their own threads; a round never waits for a server. The starts of a
 * task run one after the other, and the task gets no other decision meanwhile. A service being
 * emptied does not hold its task: while it waits for its players to move, a crashed service of
 * the task is replaced and the task may grow.
 */
public final class Autoscaler implements ServiceListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Autoscaler.class.getName());

    /** Time between two rounds. The bridges report every 5 s. */
    public static final Duration INTERVAL = Duration.ofSeconds(5);

    /** Failed starts in a row after which a task is paused. */
    public static final int FAILURE_LIMIT = 3;

    /** How long a task gets no new service after {@value #FAILURE_LIMIT} failed starts. */
    public static final Duration FAILURE_PAUSE = Duration.ofMinutes(10);

    private final ScalingTarget target;
    private final Supplier<ConfigSnapshot> config;
    private final ScalingSignals signals;
    private final Clock clock;
    private final String node;
    private final Executor actions;
    private final ExecutorService ownActions;

    private final Object lock = new Object();
    private final Map<String, Instant> lastAction = new HashMap<>();
    private final Map<String, Instant> pausedUntil = new HashMap<>();
    /** Task and group file at the moment a task was paused; a change ends the pause. */
    private final Map<String, List<Object>> pausedWith = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    /** Per task, since when it could do with one dynamic service fewer ({@code scale-down-after}). */
    private final Map<String, Instant> lowSince = new HashMap<>();
    /** Services whose players are being moved away. */
    private final Set<ServiceId> draining = new HashSet<>();
    private final Set<ServiceId> released = new HashSet<>();
    private final Set<String> busy = new HashSet<>();
    /** Running services without a known player count, since when; dropped once they report. */
    private final Map<ServiceId, Instant> unknownSince = new HashMap<>();
    /** Those of them whose unknown player count was logged; logged again after they reported. */
    private final Set<ServiceId> unknownCounts = new HashSet<>();
    private final Map<String, Hold> lastHold = new HashMap<>();
    private boolean suspended;
    private boolean closed;
    private ScheduledExecutorService scheduler;

    private Autoscaler(ScalingTarget target, Supplier<ConfigSnapshot> config, ScalingSignals signals, Clock clock,
                       Executor actions) {
        this.target = Objects.requireNonNull(target, "target");
        this.config = Objects.requireNonNull(config, "config");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.node = config.get().cloud().nodeId();
        if (actions == null) {
            this.ownActions = Executors.newCachedThreadPool(daemon("vectolyn-scaling-action"));
            this.actions = ownActions;
        } else {
            this.ownActions = null;
            this.actions = actions;
        }
    }

    /**
     * Creates the autoscaler of a node. It does nothing until {@link #start()}; register it with
     * {@code ServiceManager.addListener} so it sees crashes and stops.
     *
     * @param target  the service core
     * @param config  the configuration, {@code ConfigService::snapshot}
     * @param signals player counts and memory
     * @return the autoscaler
     */
    public static Autoscaler create(ScalingTarget target, Supplier<ConfigSnapshot> config, ScalingSignals signals) {
        return new Autoscaler(target, config, signals, Clock.systemUTC(), null);
    }

    /**
     * For tests: with a clock of its own and an executor for starts and stops
     * ({@code Runnable::run} carries them out inside {@link #tick()}).
     */
    static Autoscaler create(ScalingTarget target, Supplier<ConfigSnapshot> config, ScalingSignals signals,
                             Clock clock, Executor actions) {
        return new Autoscaler(target, config, signals, clock, Objects.requireNonNull(actions, "actions"));
    }

    /** Starts the rounds, every {@link #INTERVAL}. A second call does nothing. */
    public void start() {
        synchronized (lock) {
            if (closed || scheduler != null) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(daemon("vectolyn-scaling"));
            scheduler.scheduleWithFixedDelay(this::safeTick, INTERVAL.toMillis(), INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Suspends or resumes the rounds, for example while the {@code setup} command saves.
     *
     * @param value true to suspend
     */
    public void suspend(boolean value) {
        synchronized (lock) {
            suspended = value;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "A scaling round failed", e);
        }
    }

    /**
     * Runs one round now.
     *
     * @return the decisions of the round that were carried out or held back
     */
    public List<Decision> tick() {
        ConfigSnapshot snapshot = config.get();
        CloudConfig cloud = snapshot.cloud();
        if (!cloud.setupComplete() || !cloud.eulaAccepted()) {
            return List.of();
        }
        List<ServiceInfo> services = target.services();
        ScalingPlanner.Signals values = signals.read();
        Instant now = clock.instant();
        ScalingPlanner.Input input;
        synchronized (lock) {
            if (closed || suspended) {
                return List.of();
            }
            remember(snapshot, services, values, now);
            warnUnknownCounts(snapshot, services, values, now);
            // Copies made here, under the lock: the round goes on to change lastAction while the
            // planner's input must stay as it was read.
            input = new ScalingPlanner.Input(snapshot, node, services, values, new HashMap<>(lastAction),
                    new HashMap<>(pausedUntil), new HashMap<>(lowSince), new HashSet<>(released),
                    new HashSet<>(draining), now);
        }
        List<Decision> done = new ArrayList<>();
        Set<String> held = new HashSet<>();
        // A task below min-services gets several starts in one round; they run one after the other
        // in one action, so the task still has one action at a time.
        Map<String, List<Decision>> perTask = new LinkedHashMap<>();
        for (Decision decision : ScalingPlanner.plan(input)) {
            synchronized (lock) {
                if (closed || busy.contains(decision.task())) {
                    continue;
                }
                if (decision instanceof Held h) {
                    held.add(h.task());
                    if (lastHold.put(h.task(), h.hold()) != h.hold()) {
                        logHold(h, snapshot);
                    }
                    done.add(decision);
                    continue;
                }
            }
            perTask.computeIfAbsent(decision.task(), task -> new ArrayList<>()).add(decision);
        }
        for (Map.Entry<String, List<Decision>> entry : perTask.entrySet()) {
            String task = entry.getKey();
            List<Decision> batch = List.copyOf(entry.getValue());
            // Starts keep the task busy until they are done. A drain does not: it may take the whole
            // drain-timeout, and the task must still be filled up or grow meanwhile. Its service is
            // marked as being emptied right here, before the next round can plan, so the planner
            // never chooses it again and counts it neither for min-services nor for the load.
            boolean holdsTask = batch.stream().noneMatch(Drain.class::isInstance);
            synchronized (lock) {
                if (closed) {
                    break;
                }
                if (holdsTask) {
                    busy.add(task);
                } else {
                    batch.forEach(d -> draining.add(((Drain) d).service()));
                    lowSince.remove(task);
                }
                lastAction.put(task, now);
                lastHold.remove(task);
            }
            done.addAll(batch);
            try {
                actions.execute(() -> carryOut(task, batch, holdsTask, snapshot));
            } catch (RejectedExecutionException e) {
                synchronized (lock) {
                    release(task, batch, holdsTask);
                }
            }
        }
        synchronized (lock) {
            lastHold.keySet().retainAll(held);
        }
        return List.copyOf(done);
    }

    /**
     * Names the running services whose player count is unknown, once until they report again: such
     * a service holds back scaling of its whole group. A service is named only once its count has
     * been unknown for {@link ScalingSignals#reportGrace()}: right after it became ready, its
     * bridge has not reported yet, and that is no reason for a warning. Holds {@code lock}.
     */
    private void warnUnknownCounts(ConfigSnapshot snapshot, List<ServiceInfo> services, ScalingPlanner.Signals values,
                                   Instant at) {
        Duration grace = signals.reportGrace();
        Set<ServiceId> now = new HashSet<>();
        for (ServiceInfo s : services) {
            TaskConfig task = snapshot.tasks().get(s.task());
            GroupConfig group = snapshot.groups().get(s.group());
            if (s.state() != ServiceState.RUNNING || task == null || group == null
                    || group.software() == ServerSoftware.VELOCITY
                    || values.playersOf(s.id()).isPresent()) {
                continue;
            }
            now.add(s.id());
            Instant since = unknownSince.computeIfAbsent(s.id(), id -> at);
            if (!since.plus(grace).isAfter(at) && unknownCounts.add(s.id())) {
                LOG.log(System.Logger.Level.WARNING, "Scaling: group {0} is not scaled while {1} reports no player"
                        + " count: {2}", s.group(), s.id(), signals.whyMissing(s.id()).describe());
            }
        }
        unknownSince.keySet().retainAll(now);
        unknownCounts.retainAll(now);
    }

    /** Updates what the rounds remember. Holds {@code lock}. */
    private void remember(ConfigSnapshot snapshot, List<ServiceInfo> services, ScalingPlanner.Signals values,
                          Instant now) {
        Set<ServiceId> present = new HashSet<>();
        for (ServiceInfo s : services) {
            present.add(s.id());
            boolean stable = s.state() == ServiceState.RUNNING && s.startedAt()
                    .map(at -> !at.plusSeconds(RestartConfig.STABLE_AFTER_SECONDS).isAfter(now)).orElse(false);
            if (stable) {
                failures.remove(s.task());
            }
        }
        // The low-load condition has to hold without a break for scale-down-after.
        for (String task : snapshot.tasks().keySet()) {
            if (ScalingPlanner.scaleDownCandidate(snapshot, task, services, values, released, draining).isPresent()) {
                lowSince.putIfAbsent(task, now);
            } else {
                lowSince.remove(task);
            }
        }
        lowSince.keySet().retainAll(snapshot.tasks().keySet());
        released.retainAll(present);
        pausedUntil.entrySet().removeIf(e -> !now.isBefore(e.getValue())
                || !filesOf(snapshot, e.getKey()).equals(pausedWith.get(e.getKey())));
        pausedWith.keySet().retainAll(pausedUntil.keySet());
    }

    private void carryOut(String task, List<Decision> batch, boolean holdsTask, ConfigSnapshot snapshot) {
        try {
            for (Decision decision : batch) {
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                }
                carryOut(decision, snapshot);
            }
        } finally {
            synchronized (lock) {
                release(task, batch, holdsTask);
            }
        }
    }

    /**
     * Ends what {@link #tick} marked for a batch: the busy task, or the services being emptied.
     * Holds {@code lock}.
     */
    private void release(String task, List<Decision> batch, boolean holdsTask) {
        if (holdsTask) {
            busy.remove(task);
        }
        for (Decision decision : batch) {
            if (decision instanceof Drain drain) {
                draining.remove(drain.service());
            }
        }
    }

    private void carryOut(Decision decision, ConfigSnapshot snapshot) {
        try {
            if (decision instanceof Start start) {
                ServiceInfo info = start.index().isPresent()
                        ? target.start(start.task(), start.index().getAsInt())
                        : target.startDynamic(start.task());
                LOG.log(System.Logger.Level.INFO, "Scaling: {0} of task {1} is started ({2})", info.id(),
                        start.task(), describe(start.reason(), snapshot.tasks().get(start.task())));
            } else if (decision instanceof Drain drain) {
                drainAndStop(drain, snapshot);
            }
        } catch (ServiceException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Scaling: task {0} could not be scaled: {1}", decision.task(),
                    e.getMessage());
            if (decision instanceof Start) {
                failed(decision.task());
            }
        }
    }

    /**
     * Closes a service on the proxies, empties it, then stops it; also one that looks empty, since a
     * player may have been routed there just now. If it cannot be emptied (no other server of the
     * group, players left after the group's {@code drain-timeout} ({@link #drainTimeout}), or the
     * wait was interrupted), the stop is called off and the service keeps running; nobody is
     * kicked. If the stop fails, the service is opened again. Either way the task waits
     * {@code scale-down-after} again before the next attempt.
     */
    private void drainAndStop(Drain drain, ConfigSnapshot snapshot) throws ServiceException {
        // tick() has put the service into draining already.
        try {
            LOG.log(System.Logger.Level.INFO, "Scaling: the players of {0} of task {1} are moved to other servers ({2})",
                    drain.service(), drain.task(), describe(drain.reason(), snapshot.tasks().get(drain.task())));
            Duration timeout = drainTimeout(snapshot, drain.task());
            DrainResult result;
            try {
                result = target.drain(drain.service(), timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(System.Logger.Level.WARNING, "Scaling: {0} keeps running: moving its players was"
                        + " interrupted", drain.service());
                return;
            }
            switch (result) {
                case NO_TARGET -> {
                    LOG.log(System.Logger.Level.WARNING, "Scaling: {0} keeps running: no other open server of its"
                            + " group can take its players", drain.service());
                    return;
                }
                case TIMEOUT -> {
                    LOG.log(System.Logger.Level.WARNING, "Scaling: {0} keeps running: players are still on it"
                            + " after {1} s, a move did not work", drain.service(), timeout.toSeconds());
                    return;
                }
                case EMPTY -> {
                    // Stopped below.
                }
            }
            LOG.log(System.Logger.Level.INFO, "Scaling: {0} of task {1} is empty and is stopped", drain.service(),
                    drain.task());
            try {
                target.stop(drain.service());
            } catch (ServiceException | RuntimeException e) {
                // It keeps running: the proxies may send players there again.
                target.reopen(drain.service());
                throw e;
            }
        } finally {
            synchronized (lock) {
                draining.remove(drain.service());
                lowSince.remove(drain.task());
            }
        }
    }

    /**
     * How long the players of a service of this task get to move before its stop is called off:
     * {@code drain-timeout} of the task's group, 30 seconds when it is not set or the task or group
     * is gone.
     *
     * @param snapshot the configuration
     * @param task     task name
     * @return the timeout
     */
    static Duration drainTimeout(ConfigSnapshot snapshot, String task) {
        TaskConfig config = snapshot.tasks().get(task);
        GroupConfig group = config == null ? null : snapshot.groups().get(config.group());
        return group == null ? GroupConfig.DEFAULT_DRAIN_TIMEOUT.duration() : group.drainTimeoutOrDefault().duration();
    }

    private static String describe(ScalingPlanner.Reason reason, TaskConfig task) {
        ScalingConfig s = task == null ? null : task.scaling();
        return switch (reason) {
            case MIN_SERVICES -> "min-services is " + (s == null ? "?" : s.minServices());
            case LOAD -> s == null ? "load" : "the load of the group has reached " + s.scaleUpAt() + " %";
            case LOW_LOAD -> s == null ? "low load"
                    : "with one service fewer the load stays below " + s.scaleDownBelow() + " %, for "
                    + s.scaleDownAfter();
            case ABOVE_MAX -> "empty, and max-services is " + (s == null ? "?" : s.maxServices());
        };
    }

    private static void logHold(Held held, ConfigSnapshot snapshot) {
        String why = switch (held.hold()) {
            case MEMORY_UNKNOWN -> "the memory of the node is not measured (is the monitor switched off in monitor.yml?)";
            case MEMORY_BUDGET -> "the maximum heaps of the services on this node would exceed its memory";
            case MEMORY_FREE -> "less memory is free than the heap of one more service";
            case PAUSED -> "its last starts failed; it is paused";
        };
        GroupConfig group = snapshot.tasks().get(held.task()) == null ? null
                : snapshot.groups().get(snapshot.tasks().get(held.task()).group());
        LOG.log(held.hold() == Hold.PAUSED ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                "Scaling: task {0} needs another service ({1} heap), but {2}", held.task(),
                group == null ? "?" : group.heap().max(), why);
    }

    /** A start of the task failed or one of its services crashed. */
    private void failed(String task) {
        ConfigSnapshot snapshot = config.get();
        synchronized (lock) {
            int count = failures.merge(task, 1, Integer::sum);
            if (count < FAILURE_LIMIT) {
                return;
            }
            failures.remove(task);
            pausedUntil.put(task, clock.instant().plus(FAILURE_PAUSE));
            pausedWith.put(task, filesOf(snapshot, task));
        }
        LOG.log(System.Logger.Level.WARNING, "Scaling: task {0} gets no new service for {1} minutes after {2} failed"
                + " starts or crashes in a row; a change of its task or group file ends the pause earlier", task,
                FAILURE_PAUSE.toMinutes(), FAILURE_LIMIT);
    }

    /** The task file and its group file, to see whether the operator changed either. */
    private static List<Object> filesOf(ConfigSnapshot snapshot, String task) {
        TaskConfig t = snapshot.tasks().get(task);
        GroupConfig g = t == null ? null : snapshot.groups().get(t.group());
        return Arrays.asList(t, g);
    }

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
        stateChanged(service, previous, config.get());
    }

    /**
     * Remembers crashes and released static services. Whether a static service stopped because its
     * group was switched off is judged by the configuration at the moment of the change
     * ({@code at}), not at delivery: a switch flipped back on in between does not hide it.
     */
    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous, ConfigSnapshot at) {
        switch (service.state()) {
            case CRASHED -> {
                // PREPARED -> CRASHED is a start that failed before its process ran; the start
                // itself reports that (carryOut), so it counts once.
                if (previous != ServiceState.PREPARED) {
                    failed(service.task());
                }
            }
            case PREPARED -> {
                synchronized (lock) {
                    released.remove(service.id());
                }
            }
            case STOPPING, STOPPED -> {
                if (service.mode() == TaskMode.STATIC && !mayRun(at, service.group())) {
                    synchronized (lock) {
                        released.add(service.id());
                    }
                }
            }
            default -> {
                // Nothing to remember.
            }
        }
    }

    /** Whether the configuration lets a service of the group run at all. */
    private static boolean mayRun(ConfigSnapshot snapshot, String groupName) {
        GroupConfig group = snapshot.groups().get(groupName);
        CloudConfig cloud = snapshot.cloud();
        return group != null && cloud.setupComplete() && cloud.eulaAccepted() && ServicePlanner.isWanted(group, cloud);
    }

    @Override
    public void output(ServiceId service, String line) {
        // Not needed.
    }

    /**
     * Ends the rounds. A start under way is not waited for: once the service core closes, it
     * refuses to start the process, and its own close stops every server.
     */
    @Override
    public void close() {
        ScheduledExecutorService rounds;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            rounds = scheduler;
        }
        if (rounds != null) {
            rounds.shutdownNow();
            try {
                rounds.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownActions != null) {
            ownActions.shutdown();
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
