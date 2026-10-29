package net.vectora.cloud.node;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Logger;
import net.vectora.cloud.api.ApiServer;
import net.vectora.cloud.api.ApiSettings;
import net.vectora.cloud.api.ApiTls;
import net.vectora.cloud.api.auth.ApiTokens;
import net.vectora.cloud.api.backend.BackupServiceAccess;
import net.vectora.cloud.api.console.ApiCommands;
import net.vectora.cloud.backup.BackupException;
import net.vectora.cloud.backup.BackupService;
import net.vectora.cloud.backup.command.BackupCommands;
import net.vectora.cloud.backup.schedule.BackupScheduler;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.model.ApiConfig;
import net.vectora.cloud.monitor.MonitorRuntime;
import net.vectora.cloud.node.ops.NodeApi;
import net.vectora.cloud.node.ops.NodeBackups;
import net.vectora.cloud.node.ops.NodeMonitoring;
import net.vectora.cloud.config.ConfigChanged;
import net.vectora.cloud.config.ConfigListener;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.RedactingFilter;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.ConsoleLogHandler;
import net.vectora.cloud.console.builtin.BuiltinCommands;
import net.vectora.cloud.database.DatabaseProvisioners;
import net.vectora.cloud.database.managed.DownloadingMariaDbBinaries;
import net.vectora.cloud.database.sql.JdbcConnector;
import net.vectora.cloud.download.archive.ExtractionLimits;
import net.vectora.cloud.download.archive.SafeExtractor;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.catalog.ArtifactCatalog;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.jre.JreInstaller;
import net.vectora.cloud.download.jre.JreManager;
import net.vectora.cloud.download.lock.SoftwareLockStore;
import net.vectora.cloud.download.provider.MariaDbProvider;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.TargetPlatform;
import net.vectora.cloud.network.CloudNetworkServer;
import net.vectora.cloud.network.NetworkSettings;
import net.vectora.cloud.node.channel.ChannelBridge;
import net.vectora.cloud.node.console.ServiceCommands;
import net.vectora.cloud.node.database.NodeDatabase;
import net.vectora.cloud.node.database.NodeDatabaseProcesses;
import net.vectora.cloud.node.jre.ManagedJavaRuntimes;
import net.vectora.cloud.node.scaling.Autoscaler;
import net.vectora.cloud.node.scaling.ScalingSignals;
import net.vectora.cloud.node.scaling.ScalingTarget;
import net.vectora.cloud.node.service.ServiceManager;
import net.vectora.cloud.node.software.ArtifactSource;
import net.vectora.cloud.node.software.BridgePlugins;
import net.vectora.cloud.node.software.LockedDownloads;
import net.vectora.cloud.node.software.SoftwareInstaller;
import net.vectora.cloud.setup.SetupEnvironment;
import net.vectora.cloud.setup.SetupException;
import net.vectora.cloud.setup.SetupResult;
import net.vectora.cloud.setup.SetupWizard;

/**
 * One running node: home lock, configuration, secrets, the service core, the database, the cloud
 * channel and the console, plus the thread that keeps the JVM alive until the node is closed.
 *
 * <p>{@link #open} prepares everything without starting anything:
 * <ol>
 *   <li>takes the home lock ({@code state/cloud.lock}, one cloud per home) and then removes what
 *       an earlier, interrupted run left behind: {@code cache/artifacts/download-*.part} and the
 *       staging and old folders in {@code jre/};</li>
 *   <li>opens the configuration (writes the default {@code cloud.yml} and {@code nodes.yml} on the
 *       first start) and generates missing secrets in {@code secrets/vectora.env};</li>
 *   <li>builds the service core with the JRE management, the database and the token check of the
 *       cloud channel.</li>
 * </ol>
 * Start order in {@link #start()}:
 * <ol>
 *   <li>write the built-in templates the home does not have ({@link BuiltInTemplates});</li>
 *   <li>watch the configuration; a change that makes a group unwanted stops its services;</li>
 *   <li>start the cloud channel ({@code nodes.yml}: host and port of this node);</li>
 *   <li>start the console (log output above the prompt, built-in and service commands);</li>
 *   <li>end processes left over from the previous run ({@link ServiceManager#recoverOrphans()});</li>
 *   <li>if the setup is complete and the EULA accepted: set up the database, start the configured
 *       services, then the autoscaler ({@code docs/cloud/scaling.md}). Otherwise a warning says how
 *       to finish the setup.</li>
 * </ol>
 * {@link #close()} reverses it: every service stops, then the database, the channel, the
 * configuration, the console, and the home lock is released.
 */
public final class CloudNode implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CloudNode.class.getName());

    private final NodeSettings settings;
    private final HomeLock homeLock;
    private final ConfigService config;
    private final SecretStore secrets;
    private final ServiceManager services;
    private final NodeDatabase database;
    private final CloudNetworkServer network;
    private final ChannelBridge channel;
    private final Console console;
    private final SetupEnvironment setupEnvironment;
    /** Monitoring, backups and the API. */
    private final Ops ops;
    private BackupScheduler backupScheduler;
    private volatile SetupResult.Status setupStatus;
    /** Set once start() has given up on the setup; a later first completion then starts the servers. */
    private volatile boolean startedWithoutSetup;
    /** Set while the {@code setup} command runs; its config changes are reconciled once it ends. */
    private volatile boolean setupRunning;
    private volatile ConfigSnapshot beforeSetup;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch closeDone = new CountDownLatch(1);
    private final Object closeLock = new Object();
    private final List<Handler> removedHandlers = new ArrayList<>();
    private ConsoleLogHandler logHandler;
    private ServiceCommands serviceCommands;
    private ConfigListener reconcile;
    private boolean closing;
    private Thread closingThread;

    private CloudNode(NodeSettings settings, HomeLock homeLock, ConfigService config, SecretStore secrets,
                      ServiceManager services, NodeDatabase database, CloudNetworkServer network,
                      ChannelBridge channel, Console console, SetupEnvironment setupEnvironment, Ops ops) {
        this.ops = ops;
        this.settings = settings;
        this.setupEnvironment = setupEnvironment;
        this.homeLock = homeLock;
        this.config = config;
        this.secrets = secrets;
        this.services = services;
        this.database = database;
        this.network = network;
        this.channel = channel;
        this.console = console;
    }

    /**
     * What {@link #open} builds, before it is built. The defaults are those of a real node; tests
     * and later modules replace single parts.
     */
    public static final class Options {

        private final ServiceManager.Builder services;
        private UnaryOperator<NetworkSettings> network = UnaryOperator.identity();
        private NodeDatabase.Factory database;
        private Console console;
        private boolean noConsole;
        private Map<String, String> environment = System.getenv();

        private Options(ServiceManager.Builder services, NodeDatabase.Factory database) {
            this.services = services;
            this.database = database;
        }

        /**
         * The builder of the service core, to replace its seams (JRE resolver, server software,
         * extra placeholders). The JRE resolver is already set to the JRE management.
         *
         * @return the builder
         */
        public ServiceManager.Builder services() {
            return services;
        }

        /**
         * Changes the settings of the cloud channel (default: host and port of this node in
         * {@code nodes.yml}, standard limits), for example to port 0 in tests.
         *
         * @param value applied to the default settings
         * @return these options
         */
        public Options network(UnaryOperator<NetworkSettings> value) {
            this.network = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Replaces how the database provisioner is created (default:
         * {@code DatabaseProvisioners.create} for {@code database.mode}).
         *
         * @param value the factory
         * @return these options
         */
        public Options database(NodeDatabase.Factory value) {
            this.database = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Uses this console instead of one on the process's terminal (tests, embedding). The node
         * registers its commands in it and starts and closes it.
         *
         * @param value the console
         * @return these options
         */
        public Options console(Console value) {
            this.console = Objects.requireNonNull(value, "value");
            this.noConsole = false;
            return this;
        }

        /**
         * Runs without a console (tests).
         *
         * @return these options
         */
        public Options noConsole() {
            this.console = null;
            this.noConsole = true;
            return this;
        }

        /**
         * Replaces the environment variables the setup assistant looks at (default: the process's
         * own; Pterodactyl's {@code SERVER_MEMORY}, {@code SERVER_PORT} and so on), for tests.
         *
         * @param value the variables
         * @return these options
         */
        public Options environment(Map<String, String> value) {
            this.environment = Map.copyOf(value);
            return this;
        }
    }

    /**
     * Takes the home lock, opens configuration and secrets of a home and builds the parts of the
     * node. Nothing is started yet.
     *
     * @param settings launcher settings
     * @param customize hook to replace parts before they are built, see {@link Options}
     * @return the node
     * @throws HomeLock.HomeLockedException if another cloud already uses the home
     * @throws IOException     if the home cannot be read or written
     * @throws ConfigException if the configuration is invalid
     * @throws EnvFormatException if {@code secrets/vectora.env} is malformed
     */
    public static CloudNode open(NodeSettings settings, Consumer<Options> customize)
            throws IOException, ConfigException, EnvFormatException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(customize, "customize");
        Path homePath = settings.home();
        HomeLock lock = HomeLock.acquire(homePath);
        ConfigService config = null;
        try {
            removeLeftovers(homePath);
            CloudHome home = CloudHome.of(homePath);
            config = ConfigService.open(home);
            SecretStore secrets = new SecretStore(home);
            secrets.ensureGenerated(new SecretGenerator());
            Downloads downloads = new Downloads(homePath);
            SoftwareInstaller software = new SoftwareInstaller(ArtifactCatalog.builtIn(), downloads::software,
                    BridgePlugins.fromClassPath());
            JreManager[] jre = new JreManager[1];
            ManagedJavaRuntimes java = new ManagedJavaRuntimes(requirement -> {
                synchronized (jre) {
                    if (jre[0] == null) {
                        jre[0] = ManagedJavaRuntimes.manager(homePath, downloads.providers(), downloads.downloader(),
                                downloads.lockStore(), TargetPlatform.current());
                    }
                }
                return jre[0].provide(requirement);
            }, software);
            ServiceManager.Builder builder = ServiceManager.builder(homePath, config::snapshot, secrets::load)
                    .javaRuntimes(java)
                    .serverSoftware(software);
            Path databaseHome = homePath.resolve(ServerKind.DATABASE.templateFolder()).resolve("runtime");
            NodeDatabase.Factory productionDatabase = (snapshot, passwords) -> DatabaseProvisioners.create(
                    snapshot.cloud().database(), passwords, databaseHome, new JdbcConnector(),
                    new DatabaseProvisioners.Managed(new DownloadingMariaDbBinaries(
                            downloads.providers().get(MariaDbProvider.ID), downloads.downloader(),
                            new SafeExtractor(ExtractionLimits.defaults()), databaseHome, null),
                            new NodeDatabaseProcesses(), TargetPlatform.current()));
            Options options = new Options(builder, productionDatabase);
            customize.accept(options);

            ConfigService configService = config;
            NodeDatabase database = new NodeDatabase(configService::snapshot, secrets::load, options.database);
            builder.startGate(database).placeholders(database);
            ServiceManager services = builder.build();
            ChannelBridge channel = new ChannelBridge(services);
            NetworkSettings networkSettings = options.network.apply(NetworkSettings.forNode(thisNode(config.snapshot())));
            CloudNetworkServer network = CloudNetworkServer.builder(networkSettings, services.authenticator())
                    .listener(channel)
                    .build();
            channel.attach(network);
            Ops ops = buildOps(settings, home, configService, secrets, services, channel, databaseHome);
            Console console = options.noConsole ? null : options.console != null ? options.console
                    : Console.builder()
                            .home(home)
                            .language(() -> configService.snapshot().cloud().language())
                            .historyFilter(line -> !containsSecret(secrets, line))
                            .build();
            SetupEnvironment setup = SetupEnvironment.of(settings.toMap(), options.environment);
            return new CloudNode(settings, lock, config, secrets, services, database, network, channel, console, setup,
                    ops);
        } catch (IOException | ConfigException | EnvFormatException | RuntimeException e) {
            if (config != null) {
                try {
                    config.close();
                } catch (IOException closeFailed) {
                    e.addSuppressed(closeFailed);
                }
            }
            lock.close();
            throw e;
        }
    }

    /**
     * Monitoring, backups and the API of the node, built in {@link #open} and run by {@link #start}.
     *
     * @param tokens     API tokens; {@code api token} works even while the API is off
     * @param monitoring the monitor
     * @param backups    the backups
     * @param api        the node behind the API
     * @param apiServer  the API server, or null while {@code api.enabled} is off or it cannot be prepared
     * @param scaler     the autoscaler, started once the configured servers have started
     */
    private record Ops(ApiTokens tokens, MonitorRuntime monitoring, BackupService backups, NodeApi api,
                       ApiServer apiServer, Autoscaler scaler) {
    }

    private static Ops buildOps(NodeSettings settings, CloudHome home, ConfigService config, SecretStore secrets,
                                ServiceManager services, ChannelBridge channel, Path databaseHome)
            throws IOException, ConfigException, EnvFormatException {
        String nodeId = config.snapshot().cloud().nodeId();
        RedactingFilter redactor = secrets.redactingFilter();
        ApiTokens tokens = ApiTokens.open(home);

        NodeMonitoring directory = new NodeMonitoring(services, config::snapshot);
        // An invalid monitor.yml fails here with every error, like the other configuration files.
        MonitorRuntime monitoring = MonitorRuntime.open(home.root(), directory, secrets, redactor::redact, nodeId);
        directory.attach(monitoring.monitor());
        services.addListener(directory);
        channel.statusListener(monitoring.monitor()::statusReported);

        NodeBackups parts = new NodeBackups(home.root(), services, config::snapshot, secrets, databaseHome);
        BackupService backups = BackupService.builder(home.root())
                .targets(parts)
                .control(parts)
                .database(parts)
                .cloudVersion(settings.launcherVersion())
                .build();

        NodeApi api = new NodeApi(services, channel, nodeId, settings.launcherVersion(), Instant.now(),
                redactor::redact, Optional.of(new BackupServiceAccess(backups)));
        services.addListener(api);
        channel.playerListener(api);

        // Player counts of the bridges and proxies, memory of the node from the monitor
        // (docs/cloud/scaling.md); the configuration is the same one the console and the API change.
        Autoscaler scaler = Autoscaler.create(ScalingTarget.of(services), config::snapshot,
                ScalingSignals.of(monitoring.monitor(), channel.players(), Clock.systemUTC()));
        services.addListener(scaler);
        return new Ops(tokens, monitoring, backups, api, apiServer(home, config, secrets, api, tokens), scaler);
    }

    /** The API server when {@code api.enabled}; a problem leaves the node running without it. */
    private static ApiServer apiServer(CloudHome home, ConfigService config, SecretStore secrets, NodeApi api,
                                       ApiTokens tokens) {
        ApiConfig settings = config.snapshot().cloud().api();
        if (settings == null || !settings.enabled()) {
            return null;
        }
        try {
            Optional<ApiTls.Material> tls = settings.tls() != null && settings.tls().enabled()
                    ? Optional.of(ApiTls.prepare(home, settings.tls(), settings.bind(),
                            secrets.load().get(ApiTls.PASSWORD_KEY)))
                    : Optional.empty();
            ApiServer server = ApiServer.builder(config, api, tokens).settings(ApiSettings.of(settings, tls)).build();
            api.attach(server);
            return server;
        } catch (IOException | EnvFormatException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The API is switched on in cloud.yml but cannot be prepared ({0});"
                    + " the node runs without it. See docs/cloud/api.md.", e.getMessage());
            return null;
        }
    }

    /** What an interrupted run can leave behind; only safe while this process holds the home lock. */
    private static void removeLeftovers(Path home) {
        try {
            int parts = new ArtifactCache(home.resolve("cache")).removeStalePartFiles();
            if (parts > 0) {
                LOG.log(System.Logger.Level.INFO, "Removed {0} unfinished download(s) from cache/artifacts", parts);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot clean up cache/artifacts: {0}", e.getMessage());
        }
        try {
            int jre = new JreInstaller(home, ExtractionLimits.defaults(), TargetPlatform.current()).removeLeftovers();
            if (jre > 0) {
                LOG.log(System.Logger.Level.INFO, "Removed {0} unfinished Java install(s) from jre/", jre);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot clean up jre/: {0}", e.getMessage());
        }
    }

    private static boolean containsSecret(SecretStore secrets, String line) {
        try {
            return !secrets.redactingFilter().redact(line).equals(line);
        } catch (IOException | EnvFormatException e) {
            return true;
        }
    }

    private static NodeConfig thisNode(ConfigSnapshot snapshot) {
        return snapshot.nodes().find(snapshot.cloud().nodeId())
                .orElseThrow(() -> new IllegalStateException("node-id of cloud.yml is not in nodes.yml"));
    }

    /**
     * Runs the start order described above and starts the thread that keeps the JVM alive.
     *
     * @throws IOException if watching the configuration cannot start or the channel cannot bind
     *                     its port
     */
    public void start() throws IOException {
        // An empty home (first start of VectoraCloud.jar) gets the templates the node carries.
        try {
            BuiltInTemplates.fromClassPath().seed(settings.home());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "The built-in templates cannot be written: {0}", e.getMessage());
        }
        config.startWatching();
        reconcile = new ConfigListener() {
            @Override
            public void configChanged(ConfigChanged change) {
                // While "setup" runs, its steps may still be rolled back; it reconciles once at the end.
                if (!setupRunning) {
                    reconcile(change.previous(), change.current());
                }
            }
        };
        config.addListener(reconcile);
        InetSocketAddress channelAddress = network.start();
        services.channelAddress(channelAddress.getAddress().getHostAddress(), channelAddress.getPort());
        requireOpen();
        // After the channel, before the first service: the monitor sees every start, and a backup
        // or restore that a crash interrupted is put right before a start renders into its folder.
        ops.monitoring().start();
        prepareConsole();
        ServiceManager.Recovery recovery = services.recoverOrphans();
        if (!recovery.terminated().isEmpty() || !recovery.unverifiable().isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Left over from the previous run: ended {0}, not verifiable {1}",
                    recovery.terminated(), recovery.unverifiable());
        }
        // Only once the orphans are gone: a scheduled backup, a restore or a start through the API
        // must not copy or render into a folder a server of the previous run still writes to.
        try {
            List<String> recovered = ops.backups().recoverInterruptedOperations();
            if (!recovered.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "Backups: put right after an interrupted run: {0}", recovered);
            }
        } catch (BackupException e) {
            LOG.log(System.Logger.Level.ERROR, "Backups: an interrupted run could not be put right: {0}", e.getMessage());
        }
        // Under the close lock: a close() that has begun either sees the schedule or none is made.
        synchronized (closeLock) {
            if (closing) {
                throw new IllegalStateException("the node is closing");
            }
            backupScheduler = new BackupScheduler(ops.backups());
            backupScheduler.start();
        }
        if (ops.apiServer() != null) {
            try {
                InetSocketAddress api = ops.apiServer().start();
                LOG.log(System.Logger.Level.INFO, "API listens on {0}:{1,number,#}", api.getHostString(), api.getPort());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.ERROR, "The API cannot start ({0}); the node runs without it.", e.getMessage());
            }
        }
        // The questions read the terminal on this thread, so the console's own read loop starts
        // only afterwards.
        boolean ready = runSetup();
        requireOpen();
        if (console != null) {
            console.start();
        }
        if (!ready || !config.snapshot().cloud().setupComplete() || !config.snapshot().cloud().eulaAccepted()) {
            startedWithoutSetup = true;
        }
        if (!ready) {
            LOG.log(System.Logger.Level.WARNING, "The setup did not finish, so no server is started. Run \"setup\" in "
                    + "the console or start again with --setup-file; see docs/cloud/setup.md.");
        } else if (!config.snapshot().cloud().setupComplete() || !config.snapshot().cloud().eulaAccepted()) {
            LOG.log(System.Logger.Level.WARNING, "The setup is not complete (cloud.yml: setup-complete, eula-accepted), "
                    + "so no server is started. See docs/cloud/node.md.");
        } else {
            services.startConfigured();
            // Only now: a round must not race the configured starts for the same index.
            ops.scaler().start();
        }
        Thread keepAlive = new Thread(() -> {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vectora-node");
        keepAlive.setDaemon(false);
        keepAlive.start();
    }

    /**
     * Brings the services in line with a changed configuration:
     * <ul>
     *   <li>{@code eula-accepted} taken back: every server is stopped, in the background, since
     *       none may run without the operator's consent;</li>
     *   <li>otherwise the services that are no longer wanted stop;</li>
     *   <li>and if the change finished the first setup after this start had to wait for it, what is
     *       configured now starts, off the listener thread.</li>
     * </ul>
     */
    void reconcile(ConfigSnapshot previous, ConfigSnapshot current) {
        if (previous.cloud().eulaAccepted() && !current.cloud().eulaAccepted()) {
            LOG.log(System.Logger.Level.WARNING, "cloud.yml: eula-accepted was taken back, so every server stops");
            Thread.ofPlatform().daemon().name("vectora-stop-without-eula").start(services::stopAll);
            return;
        }
        services.stopUnwanted();
        if (setupJustFinished(previous, current)) {
            Thread.ofPlatform().daemon().name("vectora-start-after-setup").start(() -> {
                services.startConfigured();
                ops.scaler().start();
            });
        }
    }

    /**
     * Whether a change finished the first setup while the node was already running: before it,
     * {@code setup-complete} or {@code eula-accepted} was false, now both are true, and the setup
     * assistant in {@link #start()} has already given up (so its own save does not count twice).
     */
    private boolean setupJustFinished(ConfigSnapshot previous, ConfigSnapshot current) {
        boolean before = previous.cloud().setupComplete() && previous.cloud().eulaAccepted();
        boolean now = current.cloud().setupComplete() && current.cloud().eulaAccepted();
        return !before && now && startedWithoutSetup;
    }

    /** The backups of the node; for tests. */
    BackupService backups() {
        return ops.backups();
    }

    /** The node behind the API, also while the API is off; for tests. */
    NodeApi apiBackend() {
        return ops.api();
    }

    /** The hooks the {@code setup} command calls, for tests. */
    SetupWizard.CommandHooks setupHooks() {
        return setupHooks;
    }

    /** The {@code setup} command: no reconcile while it saves, one against its start afterwards. */
    private final SetupWizard.CommandHooks setupHooks = new SetupWizard.CommandHooks() {
        @Override
        public void started() {
            beforeSetup = config.snapshot();
            setupRunning = true;
            ops.scaler().suspend(true);
        }

        @Override
        public void finished() {
            setupRunning = false;
            ConfigSnapshot before = beforeSetup;
            beforeSetup = null;
            try {
                if (before != null) {
                    reconcile(before, config.snapshot());
                }
            } finally {
                ops.scaler().suspend(false);
            }
        }
    };

    /** A close() from the shutdown hook or the console may run while start() is still busy. */
    private void requireOpen() {
        synchronized (closeLock) {
            if (closing) {
                throw new IllegalStateException("the node is closing");
            }
        }
    }

    /**
     * Runs the setup assistant: nothing once {@code setup-complete} is set, otherwise the setup file,
     * the questions on the console, or a message that says how to pass the answers. Also without a
     * console. A problem is shown in the language of the start and never stops the node; it only
     * keeps every server from starting.
     *
     * @return whether servers may start ({@link SetupResult#ready()})
     */
    private boolean runSetup() {
        try {
            SetupResult result = SetupWizard.ensureComplete(console, config, setupEnvironment);
            setupStatus = result.status();
            if (result.status() != SetupResult.Status.ALREADY_COMPLETE) {
                LOG.log(System.Logger.Level.INFO, "Setup assistant: {0}",
                        result.status().name().toLowerCase(java.util.Locale.ROOT));
            }
            return result.ready();
        } catch (SetupException e) {
            setupStatus = null;
            report(e.describe(setupEnvironment.language()));
            return false;
        } catch (IOException e) {
            setupStatus = null;
            LOG.log(System.Logger.Level.ERROR, "The setup could not be saved: {0}", e.getMessage());
            return false;
        }
    }

    /** Shows a setup problem on the console, or in the log when the node runs without one. */
    private void report(String text) {
        if (console != null) {
            console.output().error(text);
        } else {
            LOG.log(System.Logger.Level.ERROR, text);
        }
    }

    /**
     * How the setup assistant ended in {@link #start()}: empty if it did not finish (a setup file with
     * problems, no way to ask, saving failed).
     *
     * @return the status
     */
    Optional<SetupResult.Status> setupStatus() {
        return Optional.ofNullable(setupStatus);
    }

    /** Log output through the console and its commands; the console's read loop starts later. */
    private void prepareConsole() {
        if (console == null) {
            return;
        }
        RedactingFilter filter;
        try {
            filter = secrets.redactingFilter();
        } catch (IOException | EnvFormatException e) {
            LOG.log(System.Logger.Level.WARNING, "Secrets cannot be read for the log filter; log lines are not shown"
                    + " in the console");
            filter = null;
        }
        if (filter != null) {
            RedactingFilter redactor = filter;
            logHandler = console.logHandler(redactor::redact);
            Logger root = Logger.getLogger("");
            for (Handler handler : root.getHandlers()) {
                if (handler instanceof java.util.logging.ConsoleHandler) {
                    root.removeHandler(handler);
                    removedHandlers.add(handler);
                }
            }
            root.addHandler(logHandler);
        }
        BuiltinCommands.register(console, config, this::close);
        serviceCommands = new ServiceCommands(services, config, channel::status, console.output());
        services.addListener(serviceCommands);
        console.commands().register(serviceCommands.command());
        SetupWizard.registerCommand(console, config, setupEnvironment, setupHooks);
        console.commands().register(ops.monitoring().commands().monitor());
        console.commands().register(ops.monitoring().commands().alerts());
        BackupCommands.register(console.commands(), ops.backups());
        // Tokens can be made before the API is switched on.
        console.commands().register(new ApiCommands(ops.tokens()).command());
    }

    /**
     * Returns the launcher settings.
     *
     * @return the settings
     */
    public NodeSettings settings() {
        return settings;
    }

    /**
     * Returns the configuration layer, for the console and the setup assistant.
     *
     * @return the configuration service
     */
    public ConfigService config() {
        return config;
    }

    /**
     * Returns the secrets store.
     *
     * @return the store of {@code secrets/vectora.env}
     */
    public SecretStore secrets() {
        return secrets;
    }

    /**
     * Returns the service core.
     *
     * @return the service manager
     */
    public ServiceManager services() {
        return services;
    }

    /**
     * Returns the database of the node.
     *
     * @return the database part
     */
    public NodeDatabase database() {
        return database;
    }

    /**
     * Returns the cloud channel server.
     *
     * @return the server
     */
    public CloudNetworkServer network() {
        return network;
    }

    /**
     * Returns the bridge between channel and service core (last status reports).
     *
     * @return the bridge
     */
    public ChannelBridge channel() {
        return channel;
    }

    /**
     * Returns the console, for the setup assistant ({@link Console#prompter()}).
     *
     * @return the console, or empty if the node runs without one
     */
    public Optional<Console> console() {
        return Optional.ofNullable(console);
    }

    /**
     * Stops every service, then the database, the cloud channel, the configuration and the
     * console, releases the home lock and lets the JVM end. Safe to call more than once and from
     * a shutdown hook: a second call waits until the first one is done.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (closing) {
                if (closingThread == Thread.currentThread()) {
                    return;
                }
                awaitClosed();
                return;
            }
            closing = true;
            closingThread = Thread.currentThread();
        }
        // Every step runs even if an earlier one fails: a failing service stop must not leave the
        // database, the channel or the home lock behind. The services stop before the database
        // (they hold connections to it); the channel outlives both, so a bridge can still say
        // goodbye while its server shuts down.
        List<Error> errors = new ArrayList<>();
        // First: the API refuses starts, reloads and backups from now on; reads and stops still work.
        step(errors, "marking the API as shutting down", () -> {
            ops.api().beginShutdown();
            if (ops.apiServer() != null) {
                ops.apiServer().beginShutdown();
            }
        });
        step(errors, "stopping the console", () -> {
            if (console != null) {
                console.stop();
            }
        });
        step(errors, "removing the configuration listener", () -> {
            if (reconcile != null) {
                config.removeListener(reconcile);
            }
        });
        step(errors, "stopping the backup schedule", () -> {
            if (backupScheduler != null) {
                backupScheduler.close();
            }
        });
        // Before the services: no round may start a server while they stop.
        step(errors, "stopping the scaling", ops.scaler()::close);
        step(errors, "stopping the services", services::close);
        // After the last service: writes the history file and sends what is queued.
        step(errors, "closing the monitoring", ops.monitoring()::close);
        step(errors, "stopping the database", database::shutdown);
        step(errors, "closing the cloud channel", network::close);
        step(errors, "closing the configuration", config::close);
        step(errors, "closing the console", this::closeConsole);
        // Last: event streams get 1001, then the sockets close.
        step(errors, "closing the API", () -> {
            if (ops.apiServer() != null) {
                ops.apiServer().close();
            }
        });
        step(errors, "releasing the home lock", homeLock::close);
        closed.countDown();
        closeDone.countDown();
        if (!errors.isEmpty()) {
            Error first = errors.getFirst();
            errors.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    /** One step of {@link #close()}. */
    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    /**
     * Runs one step of {@link #close()} in its own try block: a failure is logged and the next step
     * runs anyway. An {@link Error} is kept and thrown once every step has run.
     */
    private static void step(List<Error> errors, String what, Step action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Shutdown: " + what + " failed", e);
        } catch (Error e) {
            LOG.log(System.Logger.Level.ERROR, "Shutdown: " + what + " failed", e);
            errors.add(e);
        }
    }

    private void closeConsole() {
        if (console == null) {
            return;
        }
        if (logHandler != null) {
            Logger root = Logger.getLogger("");
            root.removeHandler(logHandler);
            for (Handler handler : removedHandlers) {
                root.addHandler(handler);
            }
            removedHandlers.clear();
        }
        console.close();
    }

    private void awaitClosed() {
        try {
            closeDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The download infrastructure of a home, created on first use. */
    private static final class Downloads {

        private final Path home;
        private ProviderRegistry providers;
        private ArtifactDownloader downloader;
        private SoftwareLockStore lockStore;
        private LockedDownloads software;

        Downloads(Path home) {
            this.home = home;
        }

        synchronized ProviderRegistry providers() {
            init();
            return providers;
        }

        synchronized ArtifactDownloader downloader() {
            init();
            return downloader;
        }

        synchronized SoftwareLockStore lockStore() {
            init();
            return lockStore;
        }

        synchronized ArtifactSource software() {
            init();
            return software;
        }

        private void init() {
            if (providers == null) {
                DownloadClient client = DownloadClient.createDefault();
                providers = ProviderRegistry.standard(client);
                ArtifactCache cache = new ArtifactCache(home.resolve("cache"));
                downloader = new ArtifactDownloader(client, providers, cache);
                // One store for every download of the node: JREs, MariaDB and the server software.
                lockStore = SoftwareLockStore.inHome(home);
                software = new LockedDownloads(providers, downloader, cache, lockStore);
            }
        }
    }
}
