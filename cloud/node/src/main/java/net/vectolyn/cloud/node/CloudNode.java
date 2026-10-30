package net.vectolyn.cloud.node;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Logger;
import net.vectolyn.cloud.api.ApiServer;
import net.vectolyn.cloud.api.ApiSettings;
import net.vectolyn.cloud.api.ApiTls;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.backend.BackupServiceAccess;
import net.vectolyn.cloud.api.console.ApiCommands;
import net.vectolyn.cloud.backup.BackupException;
import net.vectolyn.cloud.backup.BackupService;
import net.vectolyn.cloud.backup.command.BackupCommands;
import net.vectolyn.cloud.backup.schedule.BackupScheduler;
import net.vectolyn.cloud.cluster.ClusterCommands;
import net.vectolyn.cloud.cluster.ClusterPaths;
import net.vectolyn.cloud.cluster.ClusterService;
import net.vectolyn.cloud.cluster.VersionPolicy;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.model.ApiConfig;
import net.vectolyn.cloud.monitor.MonitorRuntime;
import net.vectolyn.cloud.node.ops.NodeApi;
import net.vectolyn.cloud.node.ops.NodeBackups;
import net.vectolyn.cloud.node.ops.NodeMonitoring;
import net.vectolyn.cloud.config.ConfigChanged;
import net.vectolyn.cloud.config.ConfigListener;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.secret.EnvFormatException;
import net.vectolyn.cloud.config.secret.RedactingFilter;
import net.vectolyn.cloud.config.secret.SecretGenerator;
import net.vectolyn.cloud.config.secret.SecretStore;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.ConsoleLogHandler;
import net.vectolyn.cloud.console.LogHandlers;
import net.vectolyn.cloud.console.builtin.BuiltinCommands;
import net.vectolyn.cloud.database.DatabaseProvisioners;
import net.vectolyn.cloud.database.managed.DownloadingMariaDbBinaries;
import net.vectolyn.cloud.database.sql.JdbcConnector;
import net.vectolyn.cloud.download.archive.ExtractionLimits;
import net.vectolyn.cloud.download.archive.SafeExtractor;
import net.vectolyn.cloud.download.cache.ArtifactCache;
import net.vectolyn.cloud.download.cache.ArtifactDownloader;
import net.vectolyn.cloud.download.catalog.ArtifactCatalog;
import net.vectolyn.cloud.download.http.DownloadClient;
import net.vectolyn.cloud.download.jre.JreInstaller;
import net.vectolyn.cloud.download.jre.JreManager;
import net.vectolyn.cloud.download.lock.SoftwareLockStore;
import net.vectolyn.cloud.download.provider.MariaDbProvider;
import net.vectolyn.cloud.download.provider.ProviderRegistry;
import net.vectolyn.cloud.download.provider.TargetPlatform;
import net.vectolyn.cloud.network.CloudNetworkServer;
import net.vectolyn.cloud.network.NetworkSettings;
import net.vectolyn.cloud.node.channel.ChannelBridge;
import net.vectolyn.cloud.node.console.ServiceCommands;
import net.vectolyn.cloud.node.database.NodeDatabase;
import net.vectolyn.cloud.node.database.NodeDatabaseProcesses;
import net.vectolyn.cloud.node.jre.ManagedJavaRuntimes;
import net.vectolyn.cloud.node.scaling.Autoscaler;
import net.vectolyn.cloud.node.scaling.ScalingSignals;
import net.vectolyn.cloud.node.scaling.ScalingTarget;
import net.vectolyn.cloud.node.service.ServiceException;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.node.service.ServicePlanner;
import net.vectolyn.cloud.node.software.ArtifactSource;
import net.vectolyn.cloud.node.software.BridgePlugins;
import net.vectolyn.cloud.node.software.LockedDownloads;
import net.vectolyn.cloud.node.software.SoftwareInstaller;
import net.vectolyn.cloud.setup.SetupEnvironment;
import net.vectolyn.cloud.setup.SetupException;
import net.vectolyn.cloud.setup.SetupResult;
import net.vectolyn.cloud.setup.SetupWizard;
import net.vectolyn.protocol.NodeId;

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
 *       first start) and generates missing secrets in {@code secrets/vectolyn.env};</li>
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
    /** The built-in templates, written for the wanted servers. */
    private final BuiltInTemplates templates;
    /** {@code logs/cloud.log} on the root logger, removed in {@link #close()}. */
    private final LogHandlers.FileHandler fileLog;
    private BackupScheduler backupScheduler;
    private volatile SetupResult.Status setupStatus;
    /** Set once start() has given up on the setup; a later first completion then starts the servers. */
    private volatile boolean startedWithoutSetup;
    /** The configured services have been started once; from then on the autoscaler keeps them. */
    private final AtomicBoolean configuredStarted = new AtomicBoolean();
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
                      ChannelBridge channel, Console console, SetupEnvironment setupEnvironment, Ops ops,
                      BuiltInTemplates templates, LogHandlers.FileHandler fileLog) {
        this.ops = ops;
        this.templates = templates;
        this.fileLog = fileLog;
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
     * @throws EnvFormatException if {@code secrets/vectolyn.env} is malformed
     */
    public static CloudNode open(NodeSettings settings, Consumer<Options> customize)
            throws IOException, ConfigException, EnvFormatException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(customize, "customize");
        Path homePath = settings.home();
        HomeLock lock = HomeLock.acquire(homePath);
        ConfigService config = null;
        LogHandlers.FileHandler fileLog = null;
        try {
            // Before anything reads the home: an old layout is moved first (docs/cloud/node.md).
            HomeLayoutMigration.Result layout = HomeLayoutMigration.of(homePath).run();
            removeLeftovers(homePath);
            CloudHome home = CloudHome.of(homePath);
            try {
                config = ConfigService.open(home);
            } catch (ConfigException e) {
                StartupReport.step(StartupReport.Step.CONFIGURATION, StartupReport.Status.FAILED,
                        "config/ has errors, see below");
                throw e;
            }
            SecretStore secrets = new SecretStore(home);
            secrets.ensureGenerated(new SecretGenerator());
            fileLog = openCloudLog(home, secrets);
            ConfigSnapshot loaded = config.snapshot();
            StartupReport.step(StartupReport.Step.CONFIGURATION, StartupReport.Status.OK, loaded.groups().size()
                    + " groups, " + loaded.tasks().size() + " tasks, node " + loaded.cloud().nodeId()
                    + (layout.outcome() == HomeLayoutMigration.Outcome.MIGRATED
                            ? "; home moved to the new layout, backup in " + layout.backup().orElse("?") : ""));
            BuiltInTemplates builtIn = BuiltInTemplates.fromClassPath();
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
                    .serverSoftware(software)
                    // Shown by the proxies in /version (branding.powered-by).
                    .cloudVersion(settings.launcherVersion())
                    // A server switched on later gets its template right before its first start.
                    .templates(template -> builtIn.seed(homePath, Set.of(template)));
            Path databaseHome = home.databaseDir();
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
                    ops, builtIn, fileLog);
        } catch (IOException | ConfigException | EnvFormatException | RuntimeException e) {
            if (fileLog != null) {
                Logger.getLogger("").removeHandler(fileLog);
                fileLog.close();
            }
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
     * @param tokens     API tokens for {@code api token}, which works even while the API is off: the
     *                   store the API server uses, or, if the file could not be read at the start,
     *                   another attempt to open it each time
     * @param monitoring the monitor
     * @param backups    the backups
     * @param api        the node behind the API
     * @param apiServer  the API server, or null while {@code api.enabled} is off or it cannot be prepared
     * @param scaler     the autoscaler, started once the configured servers have started
     * @param cluster    the cluster of this node (docs/cloud/cluster.md); standalone without
     *                   {@code config/cluster.yml}
     */
    private record Ops(ApiCommands.TokenFile tokens, MonitorRuntime monitoring, BackupService backups, NodeApi api,
                       ApiServer apiServer, Autoscaler scaler, ClusterService cluster) {
    }

    private static Ops buildOps(NodeSettings settings, CloudHome home, ConfigService config, SecretStore secrets,
                                ServiceManager services, ChannelBridge channel, Path databaseHome)
            throws IOException, ConfigException, EnvFormatException {
        String nodeId = config.snapshot().cloud().nodeId();
        RedactingFilter redactor = secrets.redactingFilter();
        Optional<ApiTokens> tokens = apiTokens(home, config.snapshot().cloud().api());

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
        Autoscaler scaler = Autoscaler.create(ScalingTarget.of(services, channel), config::snapshot,
                ScalingSignals.of(monitoring.monitor(), channel.players(), channel::proxyWatches, Clock.systemUTC()));
        services.addListener(scaler);
        ApiCommands.TokenFile tokenFile = tokens.isPresent() ? tokens::get : () -> ApiTokens.open(home);
        // One version per cluster: the update system (#74) hooks in at ClusterService.Events. On a
        // member the service switches the config to the mirror mode: nodes.yml, groups and tasks
        // come from the head, and console, setup and API cannot change them here.
        ClusterService cluster = ClusterService.openOrStandalone(new ClusterPaths(home), new NodeId(nodeId),
                settings.launcherVersion(), config, VersionPolicy.sameVersion(), Clock.systemUTC());
        return new Ops(tokenFile, monitoring, backups, api, apiServer(home, config, secrets, api, tokens), scaler, cluster);
    }

    /**
     * Opens {@code secrets/api-tokens.json}. A file that cannot be read does not stop the node: with
     * the API off it is a warning, with the API on the node runs without the API, as with TLS
     * material it cannot use. The {@code api token} commands report the problem.
     */
    private static Optional<ApiTokens> apiTokens(CloudHome home, ApiConfig settings) {
        try {
            return Optional.of(ApiTokens.open(home));
        } catch (IOException | RuntimeException e) {
            boolean on = settings != null && settings.enabled();
            LOG.log(on ? System.Logger.Level.ERROR : System.Logger.Level.WARNING,
                    "secrets/" + ApiTokens.FILE_NAME + " cannot be read ({0}); "
                            + (on ? "the API is switched on in cloud.yml, but the node runs without it."
                                    : "no API token can be used until it is repaired or deleted.")
                            + " \"api token list\" shows the problem. See docs/cloud/api.md.", e.getMessage());
            return Optional.empty();
        }
    }

    /** The API server when {@code api.enabled}; a problem leaves the node running without it. */
    private static ApiServer apiServer(CloudHome home, ConfigService config, SecretStore secrets, NodeApi api,
                                       Optional<ApiTokens> tokens) {
        ApiConfig settings = config.snapshot().cloud().api();
        if (settings == null || !settings.enabled() || tokens.isEmpty()) {
            return null;
        }
        try {
            Optional<ApiTls.Material> tls = settings.tls() != null && settings.tls().enabled()
                    ? Optional.of(ApiTls.prepare(home, settings.tls(), settings.bind(),
                            secrets.load().get(ApiTls.PASSWORD_KEY)))
                    : Optional.empty();
            ApiServer server = ApiServer.builder(config, api, tokens.get()).settings(ApiSettings.of(settings, tls)).build();
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
            int parts = new ArtifactCache(CloudHome.of(home).storageDir()).removeStalePartFiles();
            if (parts > 0) {
                LOG.log(System.Logger.Level.INFO, "Removed {0} unfinished download(s) from storage/artifacts", parts);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot clean up storage/artifacts: {0}", e.getMessage());
        }
        try {
            int jre = new JreInstaller(home, ExtractionLimits.defaults(), TargetPlatform.current()).removeLeftovers();
            if (jre > 0) {
                LOG.log(System.Logger.Level.INFO, "Removed {0} unfinished Java install(s) from storage/jre", jre);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot clean up storage/jre: {0}", e.getMessage());
        }
    }

    /**
     * Opens {@code logs/cloud.log} on the root logger, with the secrets taken out of every line. A
     * problem leaves the node without the file, never without its start.
     */
    private static LogHandlers.FileHandler openCloudLog(CloudHome home, SecretStore secrets) {
        RedactingFilter redactor;
        try {
            redactor = secrets.redactingFilter();
        } catch (IOException | EnvFormatException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "logs/cloud.log is not written, the secrets cannot be read to "
                    + "take them out of it ({0})", e.getMessage());
            return null;
        }
        // The console output first: without a console (systemd, Docker) this handler stays for the
        // whole run, so it must not depend on whether the file can be opened.
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof LogHandlers.StreamHandler stream) {
                stream.redactor(redactor::redact);
            }
        }
        try {
            LogHandlers.FileHandler handler = LogHandlers.file(home.logsDir(), redactor::redact);
            Logger.getLogger("").addHandler(handler);
            return handler;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "logs/cloud.log cannot be written ({0}); the log goes to the "
                    + "console only", e.getMessage());
            return null;
        }
    }

    /** The {@code template} of every group that is wanted now. */
    private static Set<String> wantedTemplates(ConfigSnapshot snapshot) {
        Set<String> result = new TreeSet<>();
        snapshot.groups().values().stream()
                .filter(group -> ServicePlanner.isWanted(group, snapshot.cloud()))
                .forEach(group -> result.add(group.template()));
        return result;
    }

    /**
     * Writes the built-in templates of the wanted servers the home does not have yet.
     *
     * @return the templates written, or empty if writing failed (logged)
     */
    private Optional<List<String>> seedWantedTemplates(ConfigSnapshot snapshot) {
        try {
            return Optional.of(templates.seed(settings.home(), wantedTemplates(snapshot)));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "The built-in templates cannot be written: {0}", e.getMessage());
            return Optional.empty();
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
        // The cluster port of a head or the link of a member; a problem leaves the node running alone.
        try {
            ops.cluster().start();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cluster: cannot start ({0}); the node runs outside the cluster. "
                    + "See docs/cloud/cluster.md.", e.getMessage());
        }
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
            skipServerSteps("the setup did not finish");
            LOG.log(System.Logger.Level.WARNING, "The setup did not finish, so no server is started. Run \"setup\" in "
                    + "the console or start again with --setup-file; see docs/cloud/setup.md.");
        } else if (!config.snapshot().cloud().setupComplete() || !config.snapshot().cloud().eulaAccepted()) {
            skipServerSteps("the setup is not complete");
            LOG.log(System.Logger.Level.WARNING, "The setup is not complete (cloud.yml: setup-complete, eula-accepted), "
                    + "so no server is started. See docs/cloud/node.md.");
        } else {
            startConfiguredOnce();
        }
        Thread keepAlive = new Thread(() -> {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vectolyn-node");
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
            Thread.ofPlatform().daemon().name("vectolyn-stop-without-eula").start(services::stopAll);
            return;
        }
        services.stopUnwanted();
        // A server that was just switched on gets its template now (docs/cloud/node.md#built-in-templates).
        if (!wantedTemplates(current).equals(wantedTemplates(previous))) {
            seedWantedTemplates(current);
        }
        // A changed players-per-service reaches the proxies' choice of the emptiest server.
        channel.refreshCapacities();
        if (setupJustFinished(previous, current) && !configuredStarted.get()) {
            Thread.ofPlatform().daemon().name("vectolyn-start-after-setup").start(this::startConfiguredOnce);
        }
    }

    /**
     * Starts the configured services and then the autoscaler, once per run of the node. If
     * {@code eula-accepted} or {@code setup-complete} is taken back and set again later, the
     * autoscaler is already running and brings the services back itself (released static ones at
     * their index, {@code min-services}); a second {@code startConfigured} next to it would race it
     * for the same index.
     */
    private void startConfiguredOnce() {
        if (!configuredStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            startConfiguredSteps();
        } finally {
            // Only now: a round must not race the configured starts for the same index.
            ops.scaler().start();
        }
    }

    /** Steps 2 to 5 of the start output: database, software, templates, services. */
    private void startConfiguredSteps() {
        try {
            database.beforeStart();
            StartupReport.step(StartupReport.Step.DATABASE, StartupReport.Status.OK, database.endpoint()
                    .map(e -> e.storageMethod().placeholderValue() + " on " + e.host() + ":" + e.port())
                    .orElse("ready"));
        } catch (ServiceException e) {
            StartupReport.step(StartupReport.Step.DATABASE, StartupReport.Status.FAILED, e.getMessage());
            for (StartupReport.Step step : List.of(StartupReport.Step.SOFTWARE, StartupReport.Step.TEMPLATES,
                    StartupReport.Step.SERVICES)) {
                StartupReport.step(step, StartupReport.Status.SKIPPED, "the database is not ready");
            }
            return;
        }
        ConfigSnapshot snapshot = config.snapshot();
        prepareSoftware(snapshot);
        Optional<List<String>> written = seedWantedTemplates(snapshot);
        Set<String> wanted = wantedTemplates(snapshot);
        if (written.isPresent()) {
            StartupReport.step(StartupReport.Step.TEMPLATES, StartupReport.Status.OK, written.get().size() + " written"
                    + (written.get().isEmpty() ? "" : " (" + String.join(", ", written.get()) + ")") + ", "
                    + (wanted.size() - written.get().size()) + " present, for " + wanted.size() + " wanted server(s)");
        } else {
            StartupReport.step(StartupReport.Step.TEMPLATES, StartupReport.Status.FAILED, "see the error above");
        }
        List<ServiceInfo> started = services.startConfigured();
        long running = started.stream().filter(s -> s.state().hasProcess()).count();
        StartupReport.step(StartupReport.Step.SERVICES, running == started.size() ? StartupReport.Status.OK
                : running == 0 && !started.isEmpty() ? StartupReport.Status.FAILED : StartupReport.Status.PARTLY,
                running + " of " + started.size() + " started");
        StartupReport.services(started);
    }

    /** Step 3: fetches the software of every group that starts services now. */
    private void prepareSoftware(ConfigSnapshot snapshot) {
        Set<String> groups = new TreeSet<>();
        for (ServicePlanner.Planned planned : ServicePlanner.plan(snapshot, snapshot.cloud().nodeId())) {
            groups.add(planned.group());
        }
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String name : groups) {
            try {
                services.prepareSoftware(name, snapshot.groups().get(name))
                        .ifPresent(text -> done.add(name + ": " + text));
            } catch (IOException | RuntimeException e) {
                failed.add(name);
                LOG.log(System.Logger.Level.ERROR, "Software of group {0} cannot be fetched: {1}", name, e.getMessage());
            }
        }
        StartupReport.Status status = failed.isEmpty() ? StartupReport.Status.OK
                : done.isEmpty() ? StartupReport.Status.FAILED : StartupReport.Status.PARTLY;
        String detail = groups.isEmpty() ? "no service to start"
                : done.isEmpty() && failed.isEmpty() ? "fetched on each start"
                : String.join("; ", done) + (failed.isEmpty() ? "" : (done.isEmpty() ? "" : "; ") + "failed: " + failed);
        StartupReport.step(StartupReport.Step.SOFTWARE, status, detail);
    }

    /** Steps 2 to 5 while no server may start. */
    private static void skipServerSteps(String reason) {
        for (StartupReport.Step step : List.of(StartupReport.Step.DATABASE, StartupReport.Step.SOFTWARE,
                StartupReport.Step.TEMPLATES, StartupReport.Step.SERVICES)) {
            StartupReport.step(step, StartupReport.Status.SKIPPED, reason);
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
                if (handler instanceof java.util.logging.ConsoleHandler || handler instanceof LogHandlers.StreamHandler) {
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
        // Tokens can be made before the API is switched on. A token file that could not be read at
        // the start is tried again by each command, which reports the problem until it is fixed.
        console.commands().register(new ApiCommands(ops.tokens()).command());
        console.commands().register(new ClusterCommands(ops.cluster()).command());
    }

    /** The cluster of the node; for tests. */
    ClusterService cluster() {
        return ops.cluster();
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
     * @return the store of {@code secrets/vectolyn.env}
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
        step(errors, "closing the cluster", ops.cluster()::close);
        step(errors, "closing the cloud channel", network::close);
        step(errors, "closing the configuration", config::close);
        step(errors, "closing the console", this::closeConsole);
        // Last: event streams get 1001, then the sockets close.
        step(errors, "closing the API", () -> {
            if (ops.apiServer() != null) {
                ops.apiServer().close();
            }
        });
        step(errors, "closing logs/cloud.log", () -> {
            if (fileLog != null) {
                Logger.getLogger("").removeHandler(fileLog);
                fileLog.close();
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
                ArtifactCache cache = new ArtifactCache(CloudHome.of(home).storageDir());
                downloader = new ArtifactDownloader(client, providers, cache);
                // One store for every download of the node: JREs, MariaDB and the server software.
                lockStore = SoftwareLockStore.inHome(home);
                software = new LockedDownloads(providers, downloader, cache, lockStore);
            }
        }
    }
}
