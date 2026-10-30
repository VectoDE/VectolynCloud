package net.vectolyn.cloud.monitor;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectolyn.cloud.monitor.alert.Alert;
import net.vectolyn.cloud.monitor.alert.AlertEngine;
import net.vectolyn.cloud.monitor.alert.AlertEvent;
import net.vectolyn.cloud.monitor.alert.AlertListener;
import net.vectolyn.cloud.monitor.alert.AlertRule;
import net.vectolyn.cloud.monitor.alert.RuleParser;
import net.vectolyn.cloud.monitor.config.MonitorConfig;
import net.vectolyn.cloud.monitor.config.MonitorConfigLoader;
import net.vectolyn.cloud.monitor.history.HistoryFile;
import net.vectolyn.cloud.monitor.history.HistoryStore;
import net.vectolyn.cloud.monitor.metric.Metric;
import net.vectolyn.cloud.monitor.metric.MetricSource;
import net.vectolyn.cloud.monitor.metric.NodeSample;
import net.vectolyn.cloud.monitor.metric.RingBuffer;
import net.vectolyn.cloud.monitor.metric.ServiceSample;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.ServiceStatus;

/**
 * The monitor of one node. Every {@code sample-interval} it takes one sample per service and one of
 * the node, adds them to the history, evaluates the alert rules and hands the alert events to its
 * listeners (the log always, notifiers and later the API as added).
 *
 * <p>Inputs from the node, all thread-safe and cheap:
 * <ul>
 *   <li>{@link ServiceDirectory}: which services exist, their state, pid and restart count;</li>
 *   <li>{@link #statusReported(ServiceStatus)}: every status report of the cloud channel
 *       ({@code ServiceEvent.StatusReported}), with players, TPS, MSPT and heap;</li>
 *   <li>{@link #serviceStateChanged(ServiceId, ServicePhase, ServicePhase)}: every state change of the
 *       service core ({@code ServiceListener.stateChanged}), for crash counting;</li>
 *   <li>{@link MetricSource}: CPU and memory from the operating system. The process values come
 *       from a probe on a thread of its own ({@link #probeNow()}), so a slow helper program never
 *       holds up a round.</li>
 * </ul>
 *
 * <p>A status report counts for {@code stale-after}; after that the bridge values are missing.
 * Gauge rules (everything except {@code crashes} and {@code restarts}) are evaluated only while a
 * service is {@link ServicePhase#RUNNING}; when it leaves that state its gauge alerts end.
 */
public final class Monitor implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Monitor.class.getName());

    private final ServiceDirectory directory;
    private final MetricSource source;
    private final Clock clock;
    private final MonitorConfig config;
    private final AlertEngine engine;
    private final HistoryStore store;
    private final List<AlertListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<ServiceId, Track> tracks = new ConcurrentHashMap<>();
    private final RingBuffer<NodeSample> nodeHistory;
    private final Object sampling = new Object();
    private final Object probing = new Object();
    private ScheduledExecutorService timer;
    private ScheduledExecutorService probeTimer;
    private volatile ProcessReading lastProbe = ProcessReading.NONE;
    private volatile boolean closed;

    /**
     * The newest result of the process probe.
     *
     * @param values CPU and memory per pid
     * @param at     when the probe finished, or null before the first one
     */
    private record ProcessReading(Map<Long, MetricSource.ProcessMetrics> values, Instant at) {
        static final ProcessReading NONE = new ProcessReading(Map.of(), null);
    }

    private static final class Track {
        final RingBuffer<ServiceSample> history;
        final Deque<Instant> crashes = new ArrayDeque<>();
        volatile ServiceStatus status;
        volatile Instant statusAt;
        int crashTotal;

        Track(int capacity) {
            history = new RingBuffer<>(capacity);
        }
    }

    private Monitor(Builder b) {
        this.directory = b.directory;
        this.source = b.source;
        this.clock = b.clock;
        this.config = b.settings.config();
        this.engine = new AlertEngine(b.settings.rules());
        this.store = b.store;
        this.nodeHistory = new RingBuffer<>(config.history().samples());
        listeners.add(Monitor::log);
        listeners.addAll(b.listeners);
    }

    /**
     * Starts a builder.
     *
     * @param directory the node's services
     * @param source    operating system metrics
     * @param settings  checked configuration
     * @return the builder
     */
    public static Builder builder(ServiceDirectory directory, MetricSource source, MonitorConfigLoader.Settings settings) {
        return new Builder(directory, source, settings);
    }

    /** Builder of {@link Monitor}. */
    public static final class Builder {
        private final ServiceDirectory directory;
        private final MetricSource source;
        private final MonitorConfigLoader.Settings settings;
        private final List<AlertListener> listeners = new ArrayList<>();
        private Clock clock = Clock.systemUTC();
        private HistoryStore store;

        private Builder(ServiceDirectory directory, MetricSource source, MonitorConfigLoader.Settings settings) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.source = Objects.requireNonNull(source, "source");
            this.settings = Objects.requireNonNull(settings, "settings");
        }

        /** Clock for the samples (tests). */
        public Builder clock(Clock value) {
            clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /** Adds a listener for alert events, for example a {@code NotificationDispatcher}. */
        public Builder listener(AlertListener value) {
            listeners.add(Objects.requireNonNull(value, "listener"));
            return this;
        }

        /** Keeps the history in a file ({@code history.persist}). */
        public Builder historyStore(HistoryStore value) {
            store = Objects.requireNonNull(value, "store");
            return this;
        }

        /** Builds the monitor; {@link Monitor#start()} starts sampling. */
        public Monitor build() {
            return new Monitor(this);
        }
    }

    /** The configuration in use. */
    public MonitorConfig config() {
        return config;
    }

    /** Adds a listener for alert events (the API, PR 15). */
    public void addListener(AlertListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ---- inputs from the node ------------------------------------------------------------------

    /**
     * A service sent a status report. Call from the cloud channel's listener
     * ({@code ServiceEvent.StatusReported}); returns at once.
     *
     * @param status the report
     */
    public void statusReported(ServiceStatus status) {
        Objects.requireNonNull(status, "status");
        Track track = track(status.service());
        track.status = status;
        track.statusAt = clock.instant();
    }

    /**
     * A service changed its state. Call from the service core's listener; returns at once.
     *
     * @param service  the service
     * @param now      the new state
     * @param previous the state before, or null for a new service
     */
    public void serviceStateChanged(ServiceId service, ServicePhase now, ServicePhase previous) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(now, "now");
        Track track = track(service);
        if (now == ServicePhase.CRASHED && previous != ServicePhase.CRASHED) {
            synchronized (track) {
                track.crashes.addLast(clock.instant());
                track.crashTotal++;
            }
        }
        if (now != ServicePhase.RUNNING && now != ServicePhase.STARTING) {
            // A report from the previous process must not count for the next one.
            track.status = null;
            track.statusAt = null;
        }
    }

    private Track track(ServiceId id) {
        return tracks.computeIfAbsent(id, k -> new Track(config.history().samples()));
    }

    // ---- sampling ------------------------------------------------------------------------------

    /**
     * Starts sampling every {@code sample-interval} on the daemon thread {@code vectolyn-monitor},
     * and, with {@code history.persist}, reads the history file first and writes it every
     * {@code persist-interval}. The process probe (CPU and memory of the server processes) runs on
     * a thread of its own, {@code vectolyn-monitor-probe}, also every {@code sample-interval}: its
     * helper program ({@code tasklist} on Windows) can take up to 30 s on a busy machine, and a
     * round must not wait for it. Does nothing if {@code enabled} is false.
     */
    public synchronized void start() {
        if (timer != null || closed || !config.enabled()) {
            return;
        }
        if (store != null) {
            store.load(clock.instant()).ifPresent(this::restore);
        }
        timer = Executors.newSingleThreadScheduledExecutor(daemon("vectolyn-monitor"));
        probeTimer = Executors.newSingleThreadScheduledExecutor(daemon("vectolyn-monitor-probe"));
        long interval = config.sampleInterval().duration().toMillis();
        // The probe starts at once, so the first round already has its values.
        probeTimer.scheduleWithFixedDelay(this::probeSafely, 0, interval, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::sampleSafely, interval, interval, TimeUnit.MILLISECONDS);
        if (store != null) {
            long persist = config.history().persistInterval().duration().toMillis();
            timer.scheduleWithFixedDelay(this::persistSafely, persist, persist, TimeUnit.MILLISECONDS);
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private void sampleSafely() {
        try {
            sampleNow();
        } catch (RuntimeException | LinkageError e) {
            // Whatever is thrown out of a periodic task ends the task for good, silently: sampling
            // would stop, every sample would age past stale-after and the autoscaler would see no
            // player count of any service. A class a runtime image lacks is such an error.
            LOG.log(Level.WARNING, "Monitor sample failed", e);
        }
    }

    private void probeSafely() {
        try {
            probeNow();
        } catch (RuntimeException | LinkageError e) {
            // As in sampleSafely: the probe must go on after a failed round.
            LOG.log(Level.WARNING, "Monitor process probe failed", e);
        }
    }

    /**
     * Measures CPU and memory of every service process once and keeps the result for the next
     * rounds. Called by the probe thread; tests call it directly. A round never waits for it: it
     * uses the newest finished result, and none once that is older than {@code stale-after}.
     */
    public void probeNow() {
        synchronized (probing) {
            Set<Long> pids = new HashSet<>();
            for (MonitoredService s : directory.services()) {
                if (hasProcess(s.phase()) && s.pid().isPresent()) {
                    pids.add(s.pid().getAsLong());
                }
            }
            // One call for all processes (one tasklist run on Windows), never one per service.
            Map<Long, MetricSource.ProcessMetrics> values = pids.isEmpty() ? Map.of() : Map.copyOf(source.processes(pids));
            lastProbe = new ProcessReading(values, clock.instant());
        }
    }

    private void persistSafely() {
        try {
            persist();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Monitor history could not be saved: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Takes one sample of every service and of the node, evaluates the rules and delivers the
     * events. Called by the timer; tests call it directly. CPU and memory of the processes come
     * from the newest {@link #probeNow()}; the round does not wait for a probe that is running.
     *
     * @return the events of this round
     */
    public List<AlertEvent> sampleNow() {
        synchronized (sampling) {
            List<MonitoredService> services = directory.services();
            Instant now = Instant.ofEpochSecond(clock.instant().getEpochSecond());
            ProcessReading reading = lastProbe;
            Map<Long, MetricSource.ProcessMetrics> processes = reading.at() != null
                    && !reading.at().plus(config.staleAfter().duration()).isBefore(now) ? reading.values() : Map.of();
            List<AlertEvent> events = new ArrayList<>();
            Set<String> present = new HashSet<>();
            for (MonitoredService service : services) {
                present.add(service.id().value());
                Track track = track(service.id());
                ServiceSample sample = sample(now, service, track, processes);
                if (hasProcess(service.phase())) {
                    track.history.add(sample);
                }
                pruneCrashes(track, now);
                boolean running = service.phase() == ServicePhase.RUNNING;
                if (!running) {
                    events.addAll(engine.endFor(now, service.id().value(), Monitor::isEventRule));
                }
                events.addAll(engine.evaluateService(now, service.id().value(), service.group(),
                        (metric, window) -> value(metric, window, service, track, sample, running, now)));
            }
            tracks.keySet().removeIf(id -> !present.contains(id.value()));
            events.addAll(engine.retainServices(now, present::contains));
            NodeSample node = source.node(now);
            nodeHistory.add(node);
            events.addAll(engine.evaluateNode(now, (metric, window) -> node.value(metric)));
            deliver(events);
            return events;
        }
    }

    private static boolean isEventRule(AlertRule rule) {
        Metric metric = rule.condition().metric();
        return metric == Metric.CRASHES || metric == Metric.RESTARTS;
    }

    private static boolean hasProcess(ServicePhase phase) {
        return phase == ServicePhase.STARTING || phase == ServicePhase.RUNNING || phase == ServicePhase.STOPPING;
    }

    private ServiceSample sample(Instant now, MonitoredService service, Track track,
                                 Map<Long, MetricSource.ProcessMetrics> processes) {
        ServiceStatus status = track.status;
        Instant at = track.statusAt;
        boolean fresh = status != null && at != null
                && !Duration.between(at, now).minus(config.staleAfter().duration()).isPositive()
                && (service.phase() == ServicePhase.RUNNING || service.phase() == ServicePhase.STARTING);
        MetricSource.ProcessMetrics process = service.pid().isPresent() && hasProcess(service.phase())
                ? processes.getOrDefault(service.pid().getAsLong(), MetricSource.ProcessMetrics.UNKNOWN)
                : MetricSource.ProcessMetrics.UNKNOWN;
        if (!fresh) {
            return new ServiceSample(now, ServiceSample.UNKNOWN, ServiceSample.UNKNOWN, Double.NaN, Double.NaN,
                    ServiceSample.UNKNOWN, ServiceSample.UNKNOWN, process.cpu(), process.rss());
        }
        boolean ticks = !service.proxy();
        return new ServiceSample(now, status.playersOnline(), status.maxPlayers(),
                ticks ? status.tps() : Double.NaN, ticks ? status.mspt() : Double.NaN,
                status.heapUsed(), status.heapMax(), process.cpu(), process.rss());
    }

    private OptionalDouble value(Metric metric, Duration window, MonitoredService service, Track track,
                                 ServiceSample sample, boolean running, Instant now) {
        if (metric == Metric.RESTARTS) {
            return OptionalDouble.of(service.restartAttempts());
        }
        if (metric == Metric.CRASHES) {
            Instant from = now.minus(window);
            synchronized (track) {
                return OptionalDouble.of(track.crashes.stream().filter(t -> !t.isBefore(from)).count());
            }
        }
        if (!running) {
            return OptionalDouble.empty();
        }
        return sample.value(metric);
    }

    private static void pruneCrashes(Track track, Instant now) {
        Instant oldest = now.minus(RuleParser.MAX_DURATION);
        synchronized (track) {
            while (!track.crashes.isEmpty() && track.crashes.peekFirst().isBefore(oldest)) {
                track.crashes.removeFirst();
            }
        }
    }

    private void deliver(List<AlertEvent> events) {
        for (AlertEvent event : events) {
            for (AlertListener listener : listeners) {
                try {
                    listener.onAlert(event);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Alert listener failed", e);
                }
            }
        }
    }

    private static void log(AlertEvent event) {
        switch (event) {
            case AlertEvent.Fired fired -> LOG.warning("Alert " + fired.alert().summary());
            case AlertEvent.Resolved resolved -> LOG.info("Alert #" + resolved.alert().id() + " " + resolved.alert().rule()
                    + " on " + resolved.alert().subject() + " ended: " + switch (resolved.alert().resolution().orElseThrow()) {
                        case CLEARED -> "the value is healthy again";
                        case SERVICE_GONE -> "the service is no longer running";
                        case RULE_REMOVED -> "the rule was changed or removed";
                    });
            case AlertEvent.Acknowledged ack -> LOG.info("Alert #" + ack.alert().id() + " acknowledged");
        }
    }

    // ---- history -------------------------------------------------------------------------------

    private void restore(HistoryFile.Content content) {
        content.node().forEach(nodeHistory::add);
        content.services().forEach((id, samples) -> {
            Track track = track(id);
            samples.forEach(track.history::add);
        });
    }

    /**
     * Writes the history file now (only with {@code history.persist}).
     *
     * @throws IOException if writing fails
     */
    public void persist() throws IOException {
        if (store == null) {
            return;
        }
        Map<ServiceId, List<ServiceSample>> services = new java.util.LinkedHashMap<>();
        tracks.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ServiceId::value)))
                .forEach(e -> services.put(e.getKey(), e.getValue().history.snapshot()));
        store.save(new HistoryFile.Content(nodeHistory.snapshot(), services), clock.instant());
    }

    // ---- views ---------------------------------------------------------------------------------

    /** Every service of the directory with its newest sample, sorted by id. */
    public List<ServiceView> services() {
        return directory.services().stream()
                .sorted(Comparator.comparing(s -> s.id().value()))
                .map(s -> {
                    Track track = tracks.get(s.id());
                    if (track == null) {
                        return new ServiceView(s, Optional.empty(), 0);
                    }
                    int crashes;
                    synchronized (track) {
                        crashes = track.crashTotal;
                    }
                    return new ServiceView(s, Optional.ofNullable(track.history.latest()), crashes);
                })
                .toList();
    }

    /**
     * When the bridge of a service last sent a status report, for the autoscaler's warning about a
     * missing player count. A report of a previous process of the service does not count.
     *
     * @param service the service
     * @return the time, or empty if it has not reported since its process started
     */
    public Optional<Instant> lastStatusReport(ServiceId service) {
        Track track = tracks.get(service);
        return track == null ? Optional.empty() : Optional.ofNullable(track.statusAt);
    }

    /** The newest sample of one service; empty for an unknown service or before its first one. */
    public Optional<ServiceSample> latest(ServiceId service) {
        Track track = tracks.get(service);
        return track == null ? Optional.empty() : Optional.ofNullable(track.history.latest());
    }

    /** The samples of one service, oldest first; empty for an unknown service. */
    public List<ServiceSample> history(ServiceId service) {
        Track track = tracks.get(service);
        return track == null ? List.of() : track.history.snapshot();
    }

    /** The newest node sample. */
    public Optional<NodeSample> node() {
        return Optional.ofNullable(nodeHistory.latest());
    }

    /** The node samples, oldest first. */
    public List<NodeSample> nodeHistory() {
        return nodeHistory.snapshot();
    }

    /** The rules in use. */
    public List<AlertRule> rules() {
        return engine.rules();
    }

    /** Alerts that fire now. */
    public List<Alert> activeAlerts() {
        return engine.active();
    }

    /** Recently ended alerts, oldest first. */
    public List<Alert> resolvedAlerts() {
        return engine.resolved();
    }

    /**
     * Acknowledges an alert.
     *
     * @param id alert number
     * @return whether an active, not yet acknowledged alert had the number
     */
    public boolean acknowledge(int id) {
        Optional<AlertEvent> event = engine.acknowledge(id, clock.instant());
        event.ifPresent(e -> deliver(List.of(e)));
        return event.isPresent();
    }

    /**
     * Acknowledges every active alert.
     *
     * @return how many were acknowledged
     */
    public int acknowledgeAll() {
        List<AlertEvent> events = engine.acknowledgeAll(clock.instant());
        deliver(events);
        return events.size();
    }

    /**
     * Replaces the rules after {@code monitor.yml} changed. Unchanged rules keep their state;
     * alerts of changed or removed rules end.
     *
     * @param rules the new rules
     */
    public void replaceRules(List<AlertRule> rules) {
        deliver(engine.replaceRules(clock.instant(), rules));
    }

    /** Stops sampling and writes the history file one last time. */
    @Override
    public void close() {
        ScheduledExecutorService t;
        ScheduledExecutorService p;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            t = timer;
            p = probeTimer;
        }
        // The interrupt kills a helper program the probe waits for.
        for (ScheduledExecutorService executor : new ScheduledExecutorService[] {p, t}) {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
        for (ScheduledExecutorService executor : new ScheduledExecutorService[] {p, t}) {
            if (executor != null) {
                try {
                    executor.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        persistSafely();
    }
}
