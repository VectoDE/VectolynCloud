package net.vectora.cloud.node.scaling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.node.scaling.ScalingPlanner.Decision;
import net.vectora.cloud.node.scaling.ScalingPlanner.Held;
import net.vectora.cloud.node.scaling.ScalingPlanner.Hold;
import net.vectora.cloud.node.scaling.ScalingPlanner.Start;
import net.vectora.cloud.node.scaling.ScalingPlanner.Stop;
import net.vectora.cloud.node.service.ServiceException;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceListener;
import net.vectora.cloud.node.service.ServicePlanner;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;

/**
 * Scales the tasks of this node: every {@link #INTERVAL} it reads the configuration, the services
 * and the {@link ScalingSignals}, lets {@link ScalingPlanner} decide, and carries the decisions out
 * through the service core. The configuration is the one of {@code ConfigService}, so a change by
 * hand, on the console or through the API takes effect in the next round, the same way.
 *
 * <p>What it remembers between rounds:
 * <ul>
 *   <li>when each task last got a decision ({@code cooldown});</li>
 *   <li>since when each running service has had no players ({@code scale-down-after});</li>
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
 * <p>Starts and stops run on their own threads, at most one per task at a time; a round never
 * waits for a server.
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
    private final Map<ServiceId, Instant> emptySince = new HashMap<>();
    private final Set<ServiceId> released = new HashSet<>();
    private final Set<String> busy = new HashSet<>();
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
            this.ownActions = Executors.newCachedThreadPool(daemon("vectora-scaling-action"));
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
            scheduler = Executors.newSingleThreadScheduledExecutor(daemon("vectora-scaling"));
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
            input = new ScalingPlanner.Input(snapshot, node, services, values, lastAction, pausedUntil, emptySince,
                    released, now);
        }
        List<Decision> done = new ArrayList<>();
        Set<String> held = new HashSet<>();
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
                busy.add(decision.task());
                lastAction.put(decision.task(), now);
                lastHold.remove(decision.task());
            }
            done.add(decision);
            try {
                actions.execute(() -> carryOut(decision, snapshot));
            } catch (RejectedExecutionException e) {
                synchronized (lock) {
                    busy.remove(decision.task());
                }
            }
        }
        synchronized (lock) {
            lastHold.keySet().retainAll(held);
        }
        return List.copyOf(done);
    }

    /** Updates what the rounds remember. Holds {@code lock}. */
    private void remember(ConfigSnapshot snapshot, List<ServiceInfo> services, ScalingPlanner.Signals values,
                          Instant now) {
        Set<ServiceId> present = new HashSet<>();
        for (ServiceInfo s : services) {
            present.add(s.id());
            Integer players = values.players().get(s.id());
            if (s.state() == ServiceState.RUNNING && players != null && players == 0) {
                emptySince.putIfAbsent(s.id(), now);
            } else {
                emptySince.remove(s.id());
            }
            boolean stable = s.state() == ServiceState.RUNNING && s.startedAt()
                    .map(at -> !at.plusSeconds(RestartConfig.STABLE_AFTER_SECONDS).isAfter(now)).orElse(false);
            if (stable) {
                failures.remove(s.task());
            }
        }
        emptySince.keySet().retainAll(present);
        released.retainAll(present);
        pausedUntil.entrySet().removeIf(e -> !now.isBefore(e.getValue())
                || !filesOf(snapshot, e.getKey()).equals(pausedWith.get(e.getKey())));
        pausedWith.keySet().retainAll(pausedUntil.keySet());
    }

    private void carryOut(Decision decision, ConfigSnapshot snapshot) {
        try {
            if (decision instanceof Start start) {
                ServiceInfo info = start.index().isPresent()
                        ? target.start(start.task(), start.index().getAsInt())
                        : target.start(start.task());
                LOG.log(System.Logger.Level.INFO, "Scaling: {0} of task {1} is started ({2})", info.id(),
                        start.task(), describe(start.reason(), snapshot.tasks().get(start.task())));
            } else if (decision instanceof Stop stop) {
                LOG.log(System.Logger.Level.INFO, "Scaling: {0} of task {1} is stopped ({2})", stop.service(),
                        stop.task(), describe(stop.reason(), snapshot.tasks().get(stop.task())));
                target.stop(stop.service());
            }
        } catch (ServiceException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Scaling: task {0} could not be scaled: {1}", decision.task(),
                    e.getMessage());
            if (decision instanceof Start) {
                failed(decision.task());
            }
        } finally {
            synchronized (lock) {
                busy.remove(decision.task());
            }
        }
    }

    private static String describe(ScalingPlanner.Reason reason, TaskConfig task) {
        ScalingConfig s = task == null ? null : task.scaling();
        return switch (reason) {
            case MIN_SERVICES -> "min-services is " + (s == null ? "?" : s.minServices());
            case PLAYERS -> s == null ? "players"
                    : "every running service has reached " + s.scaleUpAt() + " % of " + s.playersPerService() + " players";
            case EMPTY -> s == null ? "empty" : "empty for " + s.scaleDownAfter() + ", another service has room";
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
        return java.util.Arrays.asList(t, g);
    }

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
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
                if (service.mode() == TaskMode.STATIC && !mayRun(config.get(), service.group())) {
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
