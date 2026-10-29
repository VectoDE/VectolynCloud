package net.vectora.cloud.node.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.RedactingFilter;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.network.ServiceAuthenticator;
import net.vectora.cloud.network.ServiceTokens;
import net.vectora.cloud.node.state.ServiceRecord;
import net.vectora.cloud.node.state.ServiceStateStore;
import net.vectora.cloud.node.state.ServiceStates;
import net.vectora.cloud.node.state.StateFileException;
import net.vectora.cloud.template.PlaceholderValues;
import net.vectora.cloud.template.ProxyBackend;
import net.vectora.cloud.template.ServerPlatform;
import net.vectora.cloud.template.TemplateException;
import net.vectora.cloud.template.TemplateInstaller;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceEnvironment;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.RegisterServer;

/**
 * The service core of a node: plans, prepares, starts, watches, stops and restarts the server
 * processes, and keeps {@code state/services.json} up to date.
 *
 * <p>Thread-safe. Every change to the set of services happens under one lock; the slow parts
 * (rendering a template, resolving Java, waiting for a process to stop) run outside it.
 * {@link #stop(ServiceId)} blocks until the process has ended; everything else returns quickly.
 *
 * <p>What other parts of the cloud use:
 * <ul>
 *   <li>console and API: {@link #services()}, {@link #find(ServiceId)}, {@link #start(String)},
 *       {@link #stop(ServiceId)}, {@link #restart(ServiceId)}, {@link #sendCommand(ServiceId, String)},
 *       {@link #addListener};</li>
 *   <li>cloud channel: {@link #authenticator()} (the tokens this manager issues for every start and
 *       revokes when the process ends), {@link #markReady(ServiceId)};</li>
 *   <li>JRE management, downloads, database: the {@link Builder} seams
 *       {@link JavaRuntimeResolver}, {@link ServerSoftwareProvider}, {@link PlaceholderContributor}.</li>
 * </ul>
 */
public final class ServiceManager implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ServiceManager.class.getName());

    /** Text a Paper, Fabric or Velocity server prints once it is ready. */
    static final String READY_MARKER = "Done (";

    /** Longest console command accepted by {@link #sendCommand(ServiceId, String)}. */
    public static final int MAX_COMMAND_LENGTH = 1024;

    /** Where the secrets come from; {@code SecretStore::load} in the node. */
    @FunctionalInterface
    public interface SecretSource {

        /**
         * Loads the secrets file.
         *
         * @return the secrets
         * @throws IOException        if the file cannot be read
         * @throws EnvFormatException if it is malformed
         */
        EnvFile load() throws IOException, EnvFormatException;
    }

    /**
     * Runs before services are started with {@link #start(String)} or {@link #startConfigured()},
     * for example to set up the database first. Not called for restarts after a crash.
     */
    @FunctionalInterface
    public interface StartGate {

        /**
         * Checks that services may start now, and prepares what they need.
         *
         * @throws ServiceException if they may not; the message says why
         */
        void beforeStart() throws ServiceException;
    }

    /**
     * Result of {@link #recoverOrphans()}.
     *
     * @param terminated   services whose process was still running from an earlier run and was ended
     * @param unverifiable services whose pid is alive but could not be matched for sure; their
     *                     folder is not used in this run
     */
    public record Recovery(List<ServiceId> terminated, List<ServiceId> unverifiable) {
    }

    private final Path home;
    private final Supplier<ConfigSnapshot> config;
    private final SecretSource secrets;
    private final JavaRuntimeResolver javaRuntimes;
    private final ServerSoftwareProvider software;
    private final ProcessControl processes;
    private final PortAllocator ports;
    private final List<PlaceholderContributor> contributors;
    private final ServiceStateStore store;
    private final Duration destroyGrace;
    private final Clock clock;
    private final ServiceTokens tokens = new ServiceTokens();
    private String channelHost;
    private int channelPort;
    private final StartGate startGate;
    private final String nodeId;

    private final Object lock = new Object();
    private final Map<ServiceId, Service> services = new LinkedHashMap<>();
    private final Map<String, ServiceRecord> blocked = new LinkedHashMap<>();
    /** Folders of dynamic services whose deletion is queued or running; never handed out meanwhile. */
    private final Set<Path> cleaning = new HashSet<>();
    private final List<ServiceListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService events;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private boolean closing;

    private ServiceManager(Builder b) {
        this.home = b.home;
        this.config = b.config;
        this.secrets = b.secrets;
        this.javaRuntimes = b.javaRuntimes;
        this.software = b.software;
        this.processes = b.processes;
        this.contributors = List.copyOf(b.contributors);
        this.store = new ServiceStateStore(home);
        this.destroyGrace = b.destroyGrace;
        this.clock = b.clock;
        this.startGate = b.startGate;
        ConfigSnapshot snapshot = config.get();
        this.nodeId = snapshot.cloud().nodeId();
        NodeConfig node = thisNode(snapshot);
        this.channelHost = node.host();
        this.channelPort = node.port();
        this.ports = new PortAllocator(node.servicePorts(), fixedPorts(snapshot, node),
                b.portProbe != null ? b.portProbe : PortAllocator.PortProbe.binding(node.host()));
        this.events = Executors.newSingleThreadExecutor(daemon("vectora-service-events"));
        this.workers = Executors.newCachedThreadPool(daemon("vectora-service-worker"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("vectora-service-restart"));
    }

    /**
     * Starts building a manager.
     *
     * @param home    the cloud home
     * @param config  current configuration, for example {@code ConfigService::snapshot}
     * @param secrets the secrets file
     * @return the builder
     */
    public static Builder builder(Path home, Supplier<ConfigSnapshot> config, SecretSource secrets) {
        return new Builder(home, config, secrets);
    }

    // ------------------------------------------------------------------ listeners

    /**
     * Registers a listener.
     *
     * @param listener the listener
     */
    public void addListener(ServiceListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     */
    public void removeListener(ServiceListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ queries

    /**
     * Returns every service the node knows, in creation order.
     *
     * @return snapshots of the services
     */
    public List<ServiceInfo> services() {
        synchronized (lock) {
            return services.values().stream().map(Service::info).toList();
        }
    }

    /**
     * Looks up one service.
     *
     * @param id service id
     * @return its snapshot, if the node knows it
     */
    public Optional<ServiceInfo> find(ServiceId id) {
        synchronized (lock) {
            return Optional.ofNullable(services.get(id)).map(Service::info);
        }
    }

    /**
     * The check for the cloud channel. This manager is the only place that issues and revokes
     * service tokens: a new token for every start (handed to the process in
     * {@value ServiceEnvironment#SERVICE_TOKEN}), revoked as soon as the process has ended or
     * could not be started. So only the current start of a service with a process can log in;
     * tokens are compared in constant time.
     *
     * @return the authenticator to pass to {@code CloudNetworkServer.builder}
     */
    public ServiceAuthenticator authenticator() {
        return tokens;
    }

    // ------------------------------------------------------------------ orphans

    /**
     * Looks at {@code state/services.json} from the previous run and deals with what is left:
     * <ul>
     *   <li>a process that is still alive and whose start time matches the recorded one is an
     *       orphan of this cloud; it is ended (termination request, then forced after the stop
     *       timeout of its group);</li>
     *   <li>an alive pid whose start time cannot be read is not touched, and the service's folder
     *       is not used in this run (another start in the same folder could damage its world);</li>
     *   <li>an alive pid with a different start time belongs to another program and is ignored;</li>
     *   <li>leftover folders of dynamic services below {@code temp/services/} are deleted.</li>
     * </ul>
     * A broken state file is moved aside as {@code services.json.broken-<millis>}.
     * Call once, before the first start.
     *
     * @return what was found
     */
    public Recovery recoverOrphans() {
        Optional<ServiceStates> previous;
        try {
            previous = store.load();
        } catch (IOException | StateFileException e) {
            LOG.log(System.Logger.Level.WARNING, "state/services.json cannot be read, orphaned processes from the "
                    + "previous run are not detected: {0}", e.getMessage());
            try {
                store.moveAside(clock.instant());
            } catch (IOException moveFailed) {
                LOG.log(System.Logger.Level.WARNING, "Cannot move the broken state file aside", moveFailed);
            }
            previous = Optional.empty();
        }
        List<ServiceId> terminated = new ArrayList<>();
        List<ServiceId> unverifiable = new ArrayList<>();
        List<CompletableFuture<Void>> stops = new ArrayList<>();
        Set<Path> keep = new HashSet<>();
        ConfigSnapshot snapshot = config.get();
        for (ServiceRecord r : previous.map(ServiceStates::services).orElse(List.of())) {
            if (r.pid() <= 0 || !r.state().hasProcess()) {
                continue;
            }
            Optional<ProcessHandle> handle = processes.find(r.pid());
            if (handle.isEmpty()) {
                continue;
            }
            Optional<Instant> started = processes.startTime(handle.get());
            ServiceId id = new ServiceId(r.id());
            if (started.isPresent() && r.startedAt() > 0
                    && Math.abs(started.get().toEpochMilli() - r.startedAt()) <= 1000) {
                Duration grace = stopTimeout(snapshot.groups().get(r.group()));
                LOG.log(System.Logger.Level.WARNING, "{0} (pid {1}) is still running from the previous run of the "
                        + "cloud and is ended now", id, r.pid());
                stops.add(CompletableFuture.runAsync(() -> {
                    if (!processes.terminate(handle.get(), grace)) {
                        LOG.log(System.Logger.Level.ERROR, "{0} (pid {1}) could not be ended", id, r.pid());
                    }
                }, workers));
                terminated.add(id);
            } else if (started.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "pid {0} of {1} is alive, but its start time is unknown, so it "
                        + "cannot be matched. Its folder {2} is not used until the process is gone.",
                        r.pid(), id, r.workDirectory());
                synchronized (lock) {
                    blocked.put(r.workDirectory(), r);
                    ports.claim(r.port());
                }
                keep.add(home.resolve(r.workDirectory()).normalize());
                unverifiable.add(id);
            }
        }
        for (CompletableFuture<Void> stop : stops) {
            stop.join();
        }
        cleanDynamicFolders(keep);
        synchronized (lock) {
            persist();
        }
        return new Recovery(List.copyOf(terminated), List.copyOf(unverifiable));
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts what the configuration asks for: {@code min-services} of every task on this node whose
     * group is wanted. Backends start before proxies, so the proxy's server list already contains
     * them. A service that cannot be started is logged and does not stop the others.
     *
     * <p>Nothing starts while the setup is not complete or the EULA is not accepted
     * ({@code setup-complete} and {@code eula-accepted} in {@code cloud.yml}), or while the
     * {@link StartGate} refuses; that is logged as a warning.
     *
     * @return the services after the start attempt
     */
    public List<ServiceInfo> startConfigured() {
        ConfigSnapshot snapshot = config.get();
        try {
            checkStartAllowed(snapshot.cloud());
            startGate.beforeStart();
        } catch (ServiceException e) {
            LOG.log(System.Logger.Level.WARNING, "No server is started: {0}", e.getMessage());
            return services();
        }
        List<Service> prepared = new ArrayList<>();
        for (ServicePlanner.Planned planned : ServicePlanner.plan(snapshot, nodeId)) {
            try {
                prepared.add(prepare(snapshot, planned.task(), OptionalInt.of(planned.index())));
            } catch (ServiceException e) {
                LOG.log(System.Logger.Level.ERROR, "Service {0}-{1} of task {2} not started: {3}",
                        planned.group(), planned.index(), planned.task(), e.getMessage());
            }
        }
        prepared.sort((a, b) -> Boolean.compare(a.spec.isProxy(), b.spec.isProxy()));
        for (Service service : prepared) {
            try {
                launch(service, snapshot);
            } catch (ServiceException e) {
                LOG.log(System.Logger.Level.ERROR, "Service {0} not started: {1}", service.spec.id(), e.getMessage());
            }
        }
        return services();
    }

    /**
     * Starts one more service of a task, for example from the console or the scaler. A static
     * task reuses the lowest index whose service is not running (and with it its folder); a
     * dynamic task takes the lowest free index.
     *
     * @param task task name
     * @return the started service
     * @throws ServiceException if the setup is not complete or the EULA not accepted, the
     *                          {@link StartGate} refuses, the task is unknown, its group is not
     *                          wanted or runs elsewhere, {@code max-services} is reached, or the
     *                          start fails
     */
    public ServiceInfo start(String task) throws ServiceException {
        ConfigSnapshot snapshot = config.get();
        checkStartAllowed(snapshot.cloud());
        startGate.beforeStart();
        Service service = prepare(snapshot, task, OptionalInt.empty());
        launch(service, snapshot);
        return service.info();
    }

    /**
     * Stops a service and starts it again. A static service comes back with the same id, folder
     * and (if still free) port; a dynamic one gets a fresh folder and the lowest free index of its
     * group, because its old folder is being deleted.
     *
     * @param id the service
     * @return the new start
     * @throws ServiceException if the service is unknown or cannot be started again, for the same
     *                          reasons as {@link #start(String)}
     */
    public ServiceInfo restart(ServiceId id) throws ServiceException {
        ServiceSpec spec;
        synchronized (lock) {
            Service service = services.get(id);
            if (service == null) {
                throw new ServiceException("unknown service " + id);
            }
            spec = service.spec;
        }
        stop(id);
        ConfigSnapshot snapshot = config.get();
        checkStartAllowed(snapshot.cloud());
        startGate.beforeStart();
        Service next = prepare(snapshot, spec.taskName(),
                spec.isStatic() ? OptionalInt.of(spec.index()) : OptionalInt.empty());
        launch(next, snapshot);
        return next.info();
    }

    /**
     * Stops, in the background, every service whose group is not wanted any more, for example
     * after {@code servers.lobby} was switched off in {@code cloud.yml}. Their pending restarts are
     * cancelled, and {@link #start(String)} refuses them from now on. The node calls this on every
     * configuration change.
     *
     * @return the services that are being stopped
     */
    public List<ServiceId> stopUnwanted() {
        ConfigSnapshot snapshot = config.get();
        List<ServiceId> unwanted = new ArrayList<>();
        synchronized (lock) {
            if (closing) {
                return List.of();
            }
            for (Service service : services.values()) {
                GroupConfig group = snapshot.groups().getOrDefault(service.spec.groupName(), service.spec.group());
                boolean active = service.isActive() && !service.stopRequested;
                if (active && !ServicePlanner.isWanted(group, snapshot.cloud())) {
                    unwanted.add(service.spec.id());
                }
            }
        }
        for (ServiceId id : unwanted) {
            LOG.log(System.Logger.Level.INFO, "{0} is stopped: its group is not wanted any more", id);
            try {
                workers.execute(() -> {
                    try {
                        stop(id);
                    } catch (ServiceException e) {
                        // Gone in the meantime.
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Closing; close() stops it anyway.
            }
        }
        return List.copyOf(unwanted);
    }

    /** Refuses a start before the setup is done and after {@link #close()} has begun, before the start gate runs. */
    private void checkStartAllowed(CloudConfig cloud) throws ServiceException {
        synchronized (lock) {
            if (closing) {
                throw new ServiceException("the cloud is stopping, so no server may start");
            }
        }
        if (!cloud.setupComplete() || !cloud.eulaAccepted()) {
            throw new ServiceException("the setup is not complete (cloud.yml: setup-complete and eula-accepted "
                    + "must be true), so no server may start");
        }
    }

    /**
     * Picks id, index, port and folder for a new start and registers the service as PREPARED.
     */
    private Service prepare(ConfigSnapshot snapshot, String taskName, OptionalInt wantedIndex) throws ServiceException {
        TaskConfig task = snapshot.tasks().get(taskName);
        if (task == null) {
            throw new ServiceException("task " + taskName + " does not exist");
        }
        GroupConfig group = snapshot.groups().get(task.group());
        if (group == null) {
            throw new ServiceException("group " + task.group() + " of task " + taskName + " does not exist");
        }
        if (!ServicePlanner.isWanted(group, snapshot.cloud())) {
            throw new ServiceException("group " + task.group() + " is not wanted ("
                    + ServicePlanner.unwantedBecause(group, snapshot.cloud()) + ")");
        }
        if (!ServicePlanner.runsOn(task, nodeId)) {
            throw new ServiceException("task " + taskName + " does not run on node " + nodeId);
        }
        synchronized (lock) {
            if (closing) {
                throw new ServiceException("the node is shutting down");
            }
            long active = services.values().stream()
                    .filter(s -> s.spec.taskName().equals(taskName) && s.isActive())
                    .count();
            if (active >= task.scaling().maxServices()) {
                throw new ServiceException("task " + taskName + " already runs max-services ("
                        + task.scaling().maxServices() + ")");
            }
            int index = wantedIndex.isPresent() ? wantedIndex.getAsInt() : freeIndex(task.group(), task.mode());
            ServiceId id = ServiceIds.of(task.group(), index);
            Service existing = services.get(id);
            if (existing != null && existing.isActive()) {
                throw new ServiceException(id + " is already " + existing.state.name().toLowerCase(java.util.Locale.ROOT));
            }
            Path workDirectory = ServicePlanner.workDirectory(home, group, task.mode(), id.value(), index);
            if (cleaning.contains(workDirectory)) {
                throw new ServiceException("the folder of " + id + " is still being deleted");
            }
            String relative = relative(workDirectory);
            if (blocked.containsKey(relative)) {
                throw new ServiceException("the folder of " + id + " is still used by an earlier process (pid "
                        + blocked.get(relative).pid() + ")");
            }
            for (Service other : services.values()) {
                if (other != existing && other.isActive() && other.spec.workDirectory().equals(workDirectory)) {
                    throw new ServiceException("the folder of " + id + " is already used by " + other.spec.id());
                }
            }
            int port;
            boolean allocated = false;
            if (group.software() == ServerSoftware.VELOCITY) {
                boolean legacy = ServicePlanner.isLegacy(group);
                port = legacy ? snapshot.cloud().legacyProxy().port() : snapshot.cloud().proxy().port();
                for (Service other : services.values()) {
                    if (other != existing && other.isActive() && other.spec.isProxy()
                            && other.spec.isLegacyProxy() == legacy) {
                        throw new ServiceException("only one " + (legacy ? "legacy proxy" : "proxy") + " per node: port "
                                + port + " is used by " + other.spec.id());
                    }
                }
            } else {
                port = ports.allocate(existing == null ? 0 : existing.spec.port());
                allocated = true;
            }
            ServiceSpec spec = new ServiceSpec(id, task.group(), taskName, index, group, task.mode(),
                    ServicePlanner.kindOf(group), home.resolve(group.template()).resolve("template"), workDirectory, port);
            Service service = existing != null ? existing : new Service();
            int attempts = service.restartAttempts;
            service.reset(spec);
            service.restartAttempts = attempts;
            service.portHeld = allocated;
            services.put(id, service);
            transition(service, ServiceState.PREPARED);
            return service;
        }
    }

    private int freeIndex(String group, TaskMode mode) throws ServiceException {
        Path dynamicRoot = home.resolve(ServicePlanner.DYNAMIC_DIR);
        for (int index = 1; index <= ServiceIds.MAX_INDEX; index++) {
            ServiceId id = ServiceIds.of(group, index);
            if (mode == TaskMode.DYNAMIC && cleaning.contains(dynamicRoot.resolve(id.value()))) {
                // The folder of an earlier service with this id is still being deleted.
                continue;
            }
            Service service = services.get(id);
            if (service == null || (mode == TaskMode.STATIC && !service.isActive())) {
                return index;
            }
        }
        throw new ServiceException("group " + group + " has no free service index left");
    }

    /**
     * Renders the folder, resolves Java and the jar, and starts the process. Runs without the lock.
     */
    private void launch(Service service, ConfigSnapshot snapshot) throws ServiceException {
        ServiceSpec spec = service.spec;
        Writer log = null;
        AuthToken token = null;
        try {
            synchronized (lock) {
                if (service.stopRequested || closing) {
                    throw new ServiceException("stopped before it was started");
                }
            }
            NodeConfig node = thisNode(snapshot);
            EnvFile env;
            try {
                env = secrets.load();
            } catch (EnvFormatException e) {
                throw new ServiceException("secrets/vectora.env is malformed (line " + e.line() + ")", e);
            }
            List<ProxyBackend> backends = new ArrayList<>();
            List<String> tryOrder = new ArrayList<>();
            if (spec.isProxy()) {
                synchronized (lock) {
                    proxyBackends(spec, node, snapshot, backends, tryOrder);
                }
            }
            ServerPlatform platform = platformOf(spec.group());
            PlaceholderValues values = ServicePlaceholders.build(spec, platform, snapshot, node, env, backends,
                    tryOrder, contributors);
            Optional<Secret> legacySecret = ServiceSecrets.legacyForwardingFor(platform, env);
            if (!spec.isStatic()) {
                checkDynamicRoot();
                deleteDynamicFolder(spec.workDirectory());
            }
            TemplateInstaller.install(spec.templateDir(), spec.workDirectory(), platform,
                    values, snapshot.cloud().eulaAccepted());
            JavaRuntimeResolver.JavaRuntime runtime = javaRuntimes.resolve(spec.groupName(), spec.group());
            String jar = software.provide(spec, spec.workDirectory());
            token = tokens.issue(spec.id());
            List<Secret> redact = new ArrayList<>(env.entries().stream().map(EnvFile.Entry::value).toList());
            redact.add(Secret.of(token.value()));
            RedactingFilter filter = RedactingFilter.of(redact);
            List<String> command = command(runtime, spec, jar);
            Map<String, String> environment;
            synchronized (lock) {
                environment = environment(spec, platform, legacySecret, token, channelHost, channelPort);
            }
            log = ServiceOutput.openLog(home.resolve(ServiceOutput.LOG_DIR), spec.id().value());

            Process process;
            synchronized (lock) {
                if (service.stopRequested || closing) {
                    throw new ServiceException("stopped before it was started");
                }
                process = processes.start(command, spec.workDirectory(), environment);
                service.process = process;
                service.token = token;
                service.stdin = process.getOutputStream();
                service.pid = process.pid();
                service.startedAt = processes.startTime(process.toHandle()).orElse(clock.instant());
                service.exitCode = null;
                service.finished = new CompletableFuture<>();
                transition(service, ServiceState.STARTING);
            }
            Writer logWriter = log;
            log = null;
            Thread pump = new Thread(new ServiceOutput(process.getInputStream(), logWriter, filter,
                    line -> onOutput(service, process, line)), "vectora-output-" + spec.id());
            pump.setDaemon(true);
            service.pump = pump;
            pump.start();
            process.onExit().whenCompleteAsync((p, e) -> onExit(service, process, pump), workers);
        } catch (IOException | TemplateException | RuntimeException e) {
            fail(service, token, e.getMessage() == null ? e.toString() : e.getMessage());
            throw new ServiceException("cannot start " + spec.id() + ": " + e.getMessage(), e);
        } catch (ServiceException e) {
            fail(service, token, e.getMessage());
            throw e;
        } finally {
            if (log != null) {
                try {
                    log.close();
                } catch (IOException ignored) {
                    // Nothing was written to it.
                }
            }
        }
    }

    private void fail(Service service, AuthToken token, String reason) {
        synchronized (lock) {
            if (token != null && service.process == null) {
                // Only this start's token: a newer start of the same id keeps its own.
                tokens.remove(service.spec.id(), token);
            }
            if (service.state == ServiceState.PREPARED) {
                LOG.log(System.Logger.Level.WARNING, "{0} could not be started: {1}", service.spec.id(), reason);
                transition(service, service.stopRequested ? ServiceState.STOPPED : ServiceState.CRASHED);
            }
            releasePort(service);
            if (!service.spec.isStatic() && services.get(service.spec.id()) == service && !service.isActive()) {
                services.remove(service.spec.id());
                cleanupLater(service.spec.workDirectory());
                persist();
            }
        }
    }

    /**
     * The backends a proxy lists: for the main proxy every static backend except the 1.8.8 ones,
     * for the legacy proxy only the 1.8.8 ones (PandaSpigot). Only active services of groups that
     * are still wanted in {@code snapshot}. Needs the lock.
     */
    private void proxyBackends(ServiceSpec proxy, NodeConfig node, ConfigSnapshot snapshot, List<ProxyBackend> backends,
                               List<String> tryOrder) {
        boolean legacyProxy = proxy.isLegacyProxy();
        for (Service other : services.values()) {
            ServiceSpec s = other.spec;
            if (s.isProxy() || !s.isStatic() || !other.isActive() || other.stopRequested
                    || !isStillWanted(s, snapshot)) {
                // A group switched off a moment ago is still stopping; the proxy must not list it.
                continue;
            }
            if ((s.group().software() == ServerSoftware.PANDASPIGOT) != legacyProxy) {
                continue;
            }
            String name = proxyName(s);
            backends.add(new ProxyBackend(name, node.host(), s.port(), true));
            if (s.kind().orElse(null) == ServerKind.LOBBY || legacyProxy) {
                tryOrder.add(name);
            }
        }
    }

    /**
     * The name a backend has in a proxy's server list. The first static service of a group appears
     * under the group name, so plugins can keep addressing "lobby" or "end"; every other one, also
     * every dynamic one, under its service id.
     */
    private static String proxyName(ServiceSpec spec) {
        return spec.isStatic() && spec.index() == 1 ? spec.groupName() : spec.id().value();
    }

    /**
     * The {@code RegisterServer} message that adds a backend to the proxies of its kind: under the
     * name it has in {@code velocity.toml} (see {@link #proxyName}), at the node's host and the
     * service's port, with {@code legacy} set for a PandaSpigot server. Only for a backend that is
     * RUNNING, not being stopped, and whose group is still wanted; never for a proxy.
     *
     * @param backend the service
     * @return the message, or empty if the service must not be listed by any proxy right now
     */
    public Optional<RegisterServer> registration(ServiceId backend) {
        ConfigSnapshot snapshot = config.get();
        String host = thisNode(snapshot).host();
        synchronized (lock) {
            Service service = services.get(backend);
            if (service == null || !isListable(service, snapshot)) {
                return Optional.empty();
            }
            return Optional.of(registerMessage(service.spec, host));
        }
    }

    /**
     * Every {@link #registration} a proxy of one kind should have: all listable backends for the main
     * proxy ({@code legacy = false}) or for the legacy proxy ({@code legacy = true}).
     *
     * @param legacy which proxy
     * @return service id to message, in creation order of the services
     */
    public Map<ServiceId, RegisterServer> registrations(boolean legacy) {
        ConfigSnapshot snapshot = config.get();
        String host = thisNode(snapshot).host();
        Map<ServiceId, RegisterServer> result = new LinkedHashMap<>();
        synchronized (lock) {
            for (Service service : services.values()) {
                if (isListable(service, snapshot) && isLegacyBackend(service.spec) == legacy) {
                    result.put(service.spec.id(), registerMessage(service.spec, host));
                }
            }
        }
        return result;
    }

    /**
     * The proxies of one kind that are running now: the main proxy ({@code legacy = false}) or the
     * legacy proxy ({@code legacy = true}).
     *
     * @param legacy which proxy
     * @return their ids
     */
    public List<ServiceId> runningProxies(boolean legacy) {
        synchronized (lock) {
            return services.values().stream()
                    .filter(s -> s.spec.isProxy() && s.spec.isLegacyProxy() == legacy && s.state == ServiceState.RUNNING)
                    .map(s -> s.spec.id())
                    .toList();
        }
    }

    /**
     * Tells whether a service is a proxy, and which one.
     *
     * @param id the service
     * @return {@code false} for the main proxy, {@code true} for the legacy proxy, empty for a
     *         backend or an unknown service
     */
    public Optional<Boolean> proxyKind(ServiceId id) {
        synchronized (lock) {
            Service service = services.get(id);
            return service != null && service.spec.isProxy()
                    ? Optional.of(service.spec.isLegacyProxy()) : Optional.empty();
        }
    }

    /**
     * Turns the server name a proxy reports (for example in {@code PlayerJoin}) into the service it
     * means: {@code lobby} is the static {@code lobby-1}, every other name is the service id itself.
     * Only backends the proxy may list count: PandaSpigot for the legacy proxy, every other backend
     * for the main proxy.
     *
     * @param proxy the proxy that reported the name
     * @param name  the name in its server list
     * @return the service, or empty if the name means no backend of this proxy
     */
    public Optional<ServiceId> backendNamed(ServiceId proxy, ServiceId name) {
        synchronized (lock) {
            Service sender = services.get(proxy);
            if (sender == null || !sender.spec.isProxy()) {
                return Optional.empty();
            }
            boolean legacy = sender.spec.isLegacyProxy();
            for (Service service : services.values()) {
                ServiceSpec s = service.spec;
                if (!s.isProxy() && isLegacyBackend(s) == legacy && proxyName(s).equals(name.value())) {
                    return Optional.of(s.id());
                }
            }
            return Optional.empty();
        }
    }

    /** A backend a proxy may list now. Needs the lock. */
    private static boolean isListable(Service service, ConfigSnapshot snapshot) {
        return !service.spec.isProxy() && service.state == ServiceState.RUNNING && !service.stopRequested
                && isStillWanted(service.spec, snapshot);
    }

    private static boolean isLegacyBackend(ServiceSpec spec) {
        return spec.group().software() == ServerSoftware.PANDASPIGOT;
    }

    private static RegisterServer registerMessage(ServiceSpec spec, String host) {
        return new RegisterServer(new ServiceId(proxyName(spec)), host, spec.port(), new GroupName(spec.groupName()),
                isLegacyBackend(spec));
    }

    /** Whether the group of a service is wanted in {@code snapshot}, with its current group file if it still exists. */
    private static boolean isStillWanted(ServiceSpec spec, ConfigSnapshot snapshot) {
        GroupConfig group = snapshot.groups().getOrDefault(spec.groupName(), spec.group());
        return ServicePlanner.isWanted(group, snapshot.cloud());
    }

    // ------------------------------------------------------------------ running

    private void onOutput(Service service, Process process, String line) {
        if (line.contains(READY_MARKER) && line.contains(")!")) {
            markReady(service, process);
        }
        ServiceId id = service.spec.id();
        for (ServiceListener listener : listeners) {
            try {
                listener.output(id, line);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A service listener failed", e);
            }
        }
    }

    /**
     * Marks a starting service as running, for example when its bridge reports that it is ready.
     *
     * @param id the service
     * @return whether the service was starting and is running now
     */
    public boolean markReady(ServiceId id) {
        synchronized (lock) {
            Service service = services.get(id);
            return service != null && markReady(service, service.process);
        }
    }

    private boolean markReady(Service service, Process process) {
        synchronized (lock) {
            if (service.process != process || service.state != ServiceState.STARTING) {
                return false;
            }
            transition(service, ServiceState.RUNNING);
            return true;
        }
    }

    /**
     * Sends a console command to a service, as if typed into its console.
     *
     * @param id      the service
     * @param command the command, 1 to {@value #MAX_COMMAND_LENGTH} characters without line breaks
     *                or other control characters
     * @throws ServiceException if the service has no running process or the command is invalid
     */
    public void sendCommand(ServiceId id, String command) throws ServiceException {
        if (command == null || command.isEmpty() || command.length() > MAX_COMMAND_LENGTH
                || command.chars().anyMatch(Character::isISOControl)
                || command.contains(" ") || command.contains(" ")) {
            throw new ServiceException("a command is 1 to " + MAX_COMMAND_LENGTH
                    + " characters without line breaks or control characters");
        }
        Service service;
        synchronized (lock) {
            service = services.get(id);
            if (service == null) {
                throw new ServiceException("unknown service " + id);
            }
            if (service.state != ServiceState.STARTING && service.state != ServiceState.RUNNING) {
                throw new ServiceException(id + " is not running");
            }
        }
        if (!writeLine(service, command)) {
            throw new ServiceException("cannot write to the console of " + id);
        }
    }

    private boolean writeLine(Service service, String line) {
        OutputStream stdin = service.stdin;
        if (stdin == null) {
            return false;
        }
        synchronized (service) {
            try {
                stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private void onExit(Service service, Process process, Thread pump) {
        try {
            pump.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        CompletableFuture<Void> finished;
        synchronized (lock) {
            if (service.process != process) {
                return;
            }
            finished = service.finished;
            int exit = process.exitValue();
            service.exitCode = exit;
            service.process = null;
            service.stdin = null;
            // The token dies with the process: a new start gets a new one. Only the token of this
            // process is removed, never one a later start has issued in the meantime.
            if (service.token != null) {
                tokens.remove(service.spec.id(), service.token);
                service.token = null;
            }
            releasePort(service);
            boolean requested = service.stopRequested;
            ServiceState next = requested || exit == 0 ? ServiceState.STOPPED : ServiceState.CRASHED;
            if (service.state == ServiceState.STOPPING && next == ServiceState.CRASHED) {
                next = ServiceState.STOPPED;
            }
            transition(service, next);
            if (next == ServiceState.CRASHED) {
                LOG.log(System.Logger.Level.WARNING, "{0} crashed with exit code {1}", service.spec.id(), exit);
                scheduleRestart(service);
            } else {
                LOG.log(System.Logger.Level.INFO, "{0} stopped (exit code {1})", service.spec.id(), exit);
            }
            if (!service.spec.isStatic() && service.pendingRestart == null) {
                services.remove(service.spec.id());
                cleanupLater(service.spec.workDirectory());
            }
            persist();
        }
        finished.complete(null);
    }

    private void scheduleRestart(Service service) {
        RestartConfig restart = service.spec.group().processOrDefaults().restart();
        if (closing || !restart.onCrash()) {
            return;
        }
        Instant started = service.startedAt;
        if (started != null && Duration.between(started, clock.instant()).getSeconds() >= RestartConfig.STABLE_AFTER_SECONDS) {
            service.restartAttempts = 0;
        }
        if (service.restartAttempts >= restart.maxAttempts()) {
            LOG.log(System.Logger.Level.ERROR, "{0} crashed {1} times in a row and is not restarted again",
                    service.spec.id(), service.restartAttempts + 1);
            return;
        }
        service.restartAttempts++;
        Duration delay = ServicePlanner.backoff(restart, service.restartAttempts);
        LOG.log(System.Logger.Level.INFO, "{0} is restarted in {1} s (attempt {2} of {3})", service.spec.id(),
                delay.toMillis() / 1000.0, service.restartAttempts, restart.maxAttempts());
        ServiceId id = service.spec.id();
        String task = service.spec.taskName();
        int index = service.spec.index();
        service.pendingRestart = scheduler.schedule(() -> workers.execute(() -> restart(id, task, index)),
                delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void restart(ServiceId id, String task, int index) {
        ConfigSnapshot snapshot = config.get();
        try {
            Service next;
            synchronized (lock) {
                Service service = services.get(id);
                if (service == null || closing || service.state != ServiceState.CRASHED
                        || service.pendingRestart == null) {
                    return;
                }
                // The entry stays, so the attempt count carries over. A dynamic service still gets a
                // fresh folder: launch() deletes the old one before rendering the template.
                // Clearing the pending restart and preparing happen under one lock, so a stop()
                // in between either cancels the restart or finds the service PREPARED.
                service.pendingRestart = null;
                try {
                    // The same gate as every other start: eula-accepted or setup-complete taken
                    // back by hand (or a new setup) also stops the restart after a crash.
                    checkStartAllowed(snapshot.cloud());
                    next = prepare(snapshot, task, OptionalInt.of(index));
                } catch (ServiceException e) {
                    // It stays CRASHED; a dynamic one is forgotten like after its last attempt.
                    if (!service.spec.isStatic()) {
                        services.remove(id);
                        cleanupLater(service.spec.workDirectory());
                    }
                    persist();
                    throw e;
                }
            }
            launch(next, snapshot);
        } catch (ServiceException e) {
            LOG.log(System.Logger.Level.ERROR, "Restart of {0} failed: {1}", id, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ stop

    /**
     * Stops a service and waits until its process has ended: first the stop command of its group
     * on standard input ({@code stop}, or {@code shutdown} for Velocity), then after
     * {@code stop-timeout} the termination request, then after a grace period the forced end of
     * the process and its children. A pending restart after a crash is cancelled.
     *
     * @param id the service
     * @return the service afterwards
     * @throws ServiceException if the service is unknown
     */
    public ServiceInfo stop(ServiceId id) throws ServiceException {
        Service service;
        Process process;
        CompletableFuture<Void> finished;
        synchronized (lock) {
            service = services.get(id);
            if (service == null) {
                throw new ServiceException("unknown service " + id);
            }
            if (service.pendingRestart != null) {
                service.pendingRestart.cancel(false);
                service.pendingRestart = null;
                if (service.state == ServiceState.CRASHED) {
                    transition(service, ServiceState.STOPPED);
                    if (!service.spec.isStatic()) {
                        services.remove(id);
                        cleanupLater(service.spec.workDirectory());
                    }
                    persist();
                }
            }
            service.stopRequested = true;
            process = service.process;
            finished = service.finished;
            if (service.state == ServiceState.PREPARED) {
                // Not started yet: launch() sees stopRequested and does not start the process.
                transition(service, ServiceState.STOPPED);
                releasePort(service);
                ServiceInfo info = service.info();
                if (!service.spec.isStatic()) {
                    services.remove(id);
                    cleanupLater(service.spec.workDirectory());
                }
                persist();
                return info;
            }
            if (process == null || !service.state.hasProcess()) {
                return service.info();
            }
            if (service.state != ServiceState.STOPPING) {
                transition(service, ServiceState.STOPPING);
                persist();
            }
        }
        ProcessConfig settings = service.spec.group().processOrDefaults();
        String stopCommand = settings.stopCommand() != null ? settings.stopCommand()
                : service.spec.group().software() == ServerSoftware.VELOCITY ? "shutdown" : "stop";
        writeLine(service, stopCommand);
        Duration timeout = settings.stopTimeout().duration();
        if (!waitFor(process, timeout)) {
            LOG.log(System.Logger.Level.WARNING, "{0} did not stop within {1} s, ending the process", id,
                    timeout.getSeconds());
            process.destroy();
            if (!waitFor(process, destroyGrace)) {
                LOG.log(System.Logger.Level.WARNING, "{0} still runs, killing it", id);
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                waitFor(process, destroyGrace);
            }
        }
        try {
            finished.get(destroyGrace.toMillis() + 10_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "Waiting for the end of {0} failed", id);
        }
        synchronized (lock) {
            return service.info();
        }
    }

    /**
     * Stops every service in parallel and waits for all of them.
     */
    public void stopAll() {
        List<ServiceId> ids;
        synchronized (lock) {
            ids = new ArrayList<>(services.keySet());
        }
        List<CompletableFuture<Void>> stops = new ArrayList<>();
        for (ServiceId id : ids) {
            stops.add(CompletableFuture.runAsync(() -> {
                try {
                    stop(id);
                } catch (ServiceException e) {
                    // Removed in the meantime.
                }
            }, workers));
        }
        for (CompletableFuture<Void> stop : stops) {
            stop.join();
        }
    }

    /**
     * Stops every service, cancels pending restarts and ends the internal threads. No service can
     * be started afterwards.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            for (Service service : services.values()) {
                if (service.pendingRestart != null) {
                    service.pendingRestart.cancel(false);
                }
            }
        }
        stopAll();
        synchronized (lock) {
            persist();
        }
        scheduler.shutdownNow();
        workers.shutdown();
        try {
            workers.awaitTermination(30, TimeUnit.SECONDS);
            events.shutdown();
            events.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private void transition(Service service, ServiceState next) {
        ServiceState previous = service.state;
        if (previous != null && previous != next && !previous.canBecome(next)) {
            throw new IllegalStateException(service.spec.id() + ": " + previous + " -> " + next);
        }
        service.state = next;
        ServiceInfo info = service.info();
        persist();
        try {
            events.execute(() -> {
                for (ServiceListener listener : listeners) {
                    try {
                        listener.stateChanged(info, previous);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "A service listener failed", e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Closed; nobody listens any more.
        }
    }

    /** Gives the port back once; a second call does nothing, so it cannot free a port handed out again. Needs the lock. */
    private void releasePort(Service service) {
        if (service.portHeld) {
            service.portHeld = false;
            ports.release(service.spec.port());
        }
    }

    private void persist() {
        List<ServiceRecord> records = new ArrayList<>(blocked.values());
        Set<String> folders = new HashSet<>(blocked.keySet());
        for (Service s : services.values()) {
            String folder = relative(s.spec.workDirectory());
            if (!folders.add(folder)) {
                continue;
            }
            records.add(new ServiceRecord(s.spec.id().value(), s.spec.groupName(), s.spec.taskName(), s.spec.index(),
                    s.spec.mode(), s.state, s.state.hasProcess() ? s.pid : 0,
                    s.state.hasProcess() && s.startedAt != null ? s.startedAt.toEpochMilli() : 0,
                    s.spec.port(), folder));
        }
        try {
            store.save(new ServiceStates(ServiceStates.SCHEMA_VERSION, nodeId, records));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot write state/services.json", e);
        }
    }

    private String relative(Path workDirectory) {
        return home.relativize(workDirectory).toString().replace('\\', '/');
    }

    /** Queues the deletion of a dynamic folder; the folder is not handed out until it is gone. Needs the lock. */
    private void cleanupLater(Path folder) {
        if (!cleaning.add(folder)) {
            return;
        }
        Runnable delete = () -> {
            try {
                deleteDynamicFolder(folder);
            } finally {
                synchronized (lock) {
                    cleaning.remove(folder);
                }
            }
        };
        try {
            workers.execute(delete);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Closed already; delete on this thread instead of leaving the folder behind.
            delete.run();
        }
    }

    private void cleanDynamicFolders(Set<Path> keep) {
        Path root = home.resolve(ServicePlanner.DYNAMIC_DIR);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!keep.contains(child.normalize())) {
                    deleteDynamicFolder(child);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot clean up temp/services", e);
        }
    }

    /**
     * Deletes a folder of a dynamic service. Refuses anything that is not a direct child of
     * {@code <home>/temp/services}, and never follows a symbolic link or junction: a link inside the
     * folder is removed as a link, its target stays.
     */
    private void deleteDynamicFolder(Path folder) {
        Path root = home.resolve(ServicePlanner.DYNAMIC_DIR).normalize();
        Path target = folder.toAbsolutePath().normalize();
        if (!root.equals(target.getParent())) {
            LOG.log(System.Logger.Level.ERROR, "Refusing to delete {0}: not inside temp/services", target);
            return;
        }
        try {
            checkDynamicRoot();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Refusing to delete {0}: {1}", target, e.getMessage());
            return;
        }
        // Windows keeps files of a process that just ended locked for a moment (the jar, open
        // logs), so a failed delete is retried a few times before giving up.
        for (int attempt = 1; ; attempt++) {
            try {
                deleteTree(target);
                return;
            } catch (IOException e) {
                if (attempt >= DELETE_ATTEMPTS) {
                    LOG.log(System.Logger.Level.WARNING, "Cannot delete " + target, e);
                    return;
                }
            }
            try {
                Thread.sleep(DELETE_RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(System.Logger.Level.WARNING, "Interrupted while deleting {0}", target);
                return;
            }
        }
    }

    /**
     * Makes sure that {@code temp} and {@code temp/services} are real folders inside the home and
     * not links or junctions to somewhere else: dynamic folders are created and deleted below
     * them, and a link there would let the node delete files outside the home.
     */
    private void checkDynamicRoot() throws IOException {
        Path root = home.resolve(ServicePlanner.DYNAMIC_DIR).normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Path temp = root.getParent();
            if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)
                    && !temp.toRealPath().equals(home.toRealPath().resolve(temp.getFileName()))) {
                throw new IOException(home.relativize(temp) + " is a link, not a folder of the home");
            }
            return;
        }
        Path expected = home.toRealPath().resolve(ServicePlanner.DYNAMIC_DIR).normalize();
        if (!root.toRealPath().equals(expected)) {
            throw new IOException(ServicePlanner.DYNAMIC_DIR + " is a link, not a folder of the home");
        }
    }

    private static final int DELETE_ATTEMPTS = 20;
    private static final Duration DELETE_RETRY_DELAY = Duration.ofMillis(250);

    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes top = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!top.isDirectory() || top.isSymbolicLink() || top.isOther()) {
            Files.delete(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isOther() || attrs.isSymbolicLink()) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * The template platform of a group: the legacy proxy (template {@code Legacy-Proxy}) is
     * {@link ServerPlatform#VELOCITY_LEGACY} with its own forwarding secret, PandaSpigot is
     * {@link ServerPlatform#PANDASPIGOT} (EULA, no Floodgate key).
     */
    static ServerPlatform platformOf(GroupConfig group) {
        return switch (group.software()) {
            case VELOCITY -> ServicePlanner.isLegacy(group) ? ServerPlatform.VELOCITY_LEGACY : ServerPlatform.VELOCITY;
            case PAPER -> ServerPlatform.PAPER;
            case PANDASPIGOT -> ServerPlatform.PANDASPIGOT;
            case FABRIC -> ServerPlatform.FABRIC;
        };
    }

    /**
     * The command line of a service: Java, heap, the group's JVM flags, {@code -jar} and the
     * server's own arguments ({@code --nogui} for everything but Velocity). No shell is involved,
     * so nothing in it is interpreted a second time.
     */
    static List<String> command(JavaRuntimeResolver.JavaRuntime runtime, ServiceSpec spec, String jar) {
        List<String> command = new ArrayList<>();
        command.add(runtime.executable().toString());
        command.add("-Xms" + spec.group().heap().min());
        command.add("-Xmx" + spec.group().heap().max());
        command.addAll(spec.group().processOrDefaults().jvmFlags());
        if (runtime.featureVersion() >= 18) {
            // Java 18+ writes System.out in the platform encoding unless told otherwise.
            command.add("-Dstdout.encoding=UTF-8");
            command.add("-Dstderr.encoding=UTF-8");
        }
        command.add("-jar");
        command.add(jar);
        if (spec.group().software() != ServerSoftware.VELOCITY) {
            command.add("--nogui");
        }
        return command;
    }

    /**
     * The environment of a service: the cloud's own environment without any {@code VECTORA_*}
     * variable, plus the service's id, group, task, port, platform ({@code ServerPlatform} name),
     * token and where the cloud channel listens. A PandaSpigot server also gets the forwarding
     * secret of the legacy proxy, for the {@code bungeeguard} check of its bridge; no other
     * service gets a secret of {@code vectora.env} in its environment.
     */
    static Map<String, String> environment(ServiceSpec spec, ServerPlatform platform, Optional<Secret> legacySecret,
                                           AuthToken token, String channelHost, int channelPort) {
        Map<String, String> env = new HashMap<>();
        System.getenv().forEach((key, value) -> {
            if (!key.toUpperCase(java.util.Locale.ROOT).startsWith(ServiceEnvironment.PREFIX)) {
                env.put(key, value);
            }
        });
        env.put(ServiceEnvironment.SERVICE_ID, spec.id().value());
        env.put(ServiceEnvironment.SERVICE_GROUP, spec.groupName());
        env.put(ServiceEnvironment.SERVICE_TASK, spec.taskName());
        env.put(ServiceEnvironment.SERVICE_PORT, Integer.toString(spec.port()));
        env.put(ServiceEnvironment.SERVICE_PLATFORM, platform.name());
        if (platform == ServerPlatform.PANDASPIGOT) {
            legacySecret.ifPresent(secret -> env.put(ServiceEnvironment.LEGACY_FORWARDING_SECRET, secret.reveal()));
        }
        env.put(ServiceEnvironment.SERVICE_TOKEN, token.value());
        env.put(ServiceEnvironment.CLOUD_HOST, channelHost(channelHost));
        env.put(ServiceEnvironment.CLOUD_PORT, Integer.toString(channelPort));
        return env;
    }

    /**
     * Sets where the cloud channel listens, as the node's network server has bound it. Services
     * started afterwards get it in {@value ServiceEnvironment#CLOUD_HOST} and
     * {@value ServiceEnvironment#CLOUD_PORT}. Default: host and port of this node in
     * {@code nodes.yml}.
     *
     * @param host the host or address
     * @param port the port
     */
    public void channelAddress(String host, int port) {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        synchronized (lock) {
            this.channelHost = host;
            this.channelPort = port;
        }
    }

    /**
     * Where a service on this machine reaches the channel: the node's host, or the loopback
     * address if the channel listens on every address ({@code 0.0.0.0}, {@code ::}), which is
     * not an address to connect to.
     */
    static String channelHost(String host) {
        return switch (host) {
            case "0.0.0.0" -> "127.0.0.1";
            case "::", "[::]", "0:0:0:0:0:0:0:0" -> "::1";
            default -> host;
        };
    }

    private Duration stopTimeout(GroupConfig group) {
        return group == null ? ProcessConfig.defaults().stopTimeout().duration()
                : group.processOrDefaults().stopTimeout().duration();
    }

    private static NodeConfig thisNode(ConfigSnapshot snapshot) {
        return snapshot.nodes().find(snapshot.cloud().nodeId())
                .orElseThrow(() -> new IllegalStateException("node-id of cloud.yml is not in nodes.yml"));
    }

    private static Collection<Integer> fixedPorts(ConfigSnapshot snapshot, NodeConfig node) {
        Set<Integer> fixed = new HashSet<>();
        fixed.add(snapshot.cloud().proxy().port());
        fixed.add(snapshot.cloud().proxy().bedrockPort());
        if (snapshot.cloud().legacyProxy().enabled()) {
            fixed.add(snapshot.cloud().legacyProxy().port());
        }
        if (snapshot.cloud().api().enabled()) {
            fixed.add(snapshot.cloud().api().port());
        }
        DatabaseMode mode = snapshot.cloud().database().mode();
        if (mode == DatabaseMode.MANAGED || mode == DatabaseMode.DOCKER) {
            fixed.add(snapshot.cloud().database().port());
        }
        fixed.add(node.port());
        return fixed;
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Mutable state of one service; guarded by {@link #lock}. */
    private static final class Service {
        ServiceSpec spec;
        ServiceState state;
        Process process;
        /** Token of the current process; removed from the registry when exactly this process ends. */
        AuthToken token;
        OutputStream stdin;
        boolean portHeld;
        long pid;
        Instant startedAt;
        Integer exitCode;
        boolean stopRequested;
        int restartAttempts;
        Thread pump;
        ScheduledFuture<?> pendingRestart;
        CompletableFuture<Void> finished = CompletableFuture.completedFuture(null);

        void reset(ServiceSpec next) {
            spec = next;
            process = null;
            stdin = null;
            stopRequested = false;
            pendingRestart = null;
            finished = CompletableFuture.completedFuture(null);
        }

        boolean isActive() {
            return state == ServiceState.PREPARED || state.hasProcess() || pendingRestart != null;
        }

        ServiceInfo info() {
            return new ServiceInfo(spec.id(), spec.groupName(), spec.taskName(), spec.index(), spec.mode(), state,
                    spec.port(), spec.workDirectory(), state.hasProcess() ? OptionalLong.of(pid) : OptionalLong.empty(),
                    Optional.ofNullable(startedAt), exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode),
                    restartAttempts);
        }
    }

    /** Builder for {@link ServiceManager}. */
    public static final class Builder {
        private final Path home;
        private final Supplier<ConfigSnapshot> config;
        private final SecretSource secrets;
        private JavaRuntimeResolver javaRuntimes = JavaRuntimeResolver.current();
        private ServerSoftwareProvider software = ServerSoftwareProvider.existingJar();
        private ProcessControl processes = ProcessControl.system();
        private PortAllocator.PortProbe portProbe;
        private final List<PlaceholderContributor> contributors = new ArrayList<>();
        private Duration destroyGrace = Duration.ofSeconds(10);
        private Clock clock = Clock.systemUTC();
        private StartGate startGate = () -> { };

        private Builder(Path home, Supplier<ConfigSnapshot> config, SecretSource secrets) {
            this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            this.config = Objects.requireNonNull(config, "config");
            this.secrets = Objects.requireNonNull(secrets, "secrets");
        }

        /**
         * Sets where Java comes from (default: the cloud's own JVM).
         *
         * @param value the resolver
         * @return this builder
         */
        public Builder javaRuntimes(JavaRuntimeResolver value) {
            this.javaRuntimes = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets where the server jar comes from (default: already in the folder).
         *
         * @param value the provider
         * @return this builder
         */
        public Builder serverSoftware(ServerSoftwareProvider value) {
            this.software = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Replaces the operating system access (tests).
         *
         * @param value the process control
         * @return this builder
         */
        public Builder processControl(ProcessControl value) {
            this.processes = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Replaces the check whether a port is free (default: bind on the node's host).
         *
         * @param value the probe
         * @return this builder
         */
        public Builder portProbe(PortAllocator.PortProbe value) {
            this.portProbe = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Adds a source of extra placeholder values.
         *
         * @param value the contributor
         * @return this builder
         */
        public Builder placeholders(PlaceholderContributor value) {
            this.contributors.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets how long a process may take to end after the termination request before it is
         * killed (default 10 s).
         *
         * @param value the grace period
         * @return this builder
         */
        public Builder destroyGrace(Duration value) {
            this.destroyGrace = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets what runs before services are started (default: nothing), for example the
         * database setup.
         *
         * @param value the gate
         * @return this builder
         */
        public Builder startGate(StartGate value) {
            this.startGate = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Replaces the clock (tests).
         *
         * @param value the clock
         * @return this builder
         */
        public Builder clock(Clock value) {
            this.clock = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds the manager. Reads the configuration once for this node's id and port range;
         * changing {@code service-ports} needs a restart of the cloud.
         *
         * @return the manager
         * @throws IllegalStateException if {@code node-id} of {@code cloud.yml} is not in {@code nodes.yml}
         */
        public ServiceManager build() {
            return new ServiceManager(this);
        }
    }
}
