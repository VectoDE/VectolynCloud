package net.vectora.cloud.node;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Logger;
import net.vectora.cloud.config.CloudHome;
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
import net.vectora.cloud.node.service.ServiceManager;

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
 *   <li>watch the configuration; a change that makes a group unwanted stops its services;</li>
 *   <li>start the cloud channel ({@code nodes.yml}: host and port of this node);</li>
 *   <li>start the console (log output above the prompt, built-in and service commands);</li>
 *   <li>end processes left over from the previous run ({@link ServiceManager#recoverOrphans()});</li>
 *   <li>if the setup is complete and the EULA accepted: set up the database, then start the
 *       configured services. Otherwise a warning says how to finish the setup.</li>
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
                      ChannelBridge channel, Console console) {
        this.settings = settings;
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
            JreManager[] jre = new JreManager[1];
            ManagedJavaRuntimes java = new ManagedJavaRuntimes(requirement -> {
                synchronized (jre) {
                    if (jre[0] == null) {
                        jre[0] = ManagedJavaRuntimes.manager(homePath, downloads.providers(), downloads.downloader(),
                                downloads.lockStore(), TargetPlatform.current());
                    }
                }
                return jre[0].provide(requirement);
            }, ManagedJavaRuntimes.Minimums.NONE);
            ServiceManager.Builder builder = ServiceManager.builder(homePath, config::snapshot, secrets::load)
                    .javaRuntimes(java);
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
            Console console = options.noConsole ? null : options.console != null ? options.console
                    : Console.builder()
                            .home(home)
                            .language(() -> configService.snapshot().cloud().language())
                            .historyFilter(line -> !containsSecret(secrets, line))
                            .build();
            return new CloudNode(settings, lock, config, secrets, services, database, network, channel, console);
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
        config.startWatching();
        reconcile = new ConfigListener() {
            @Override
            public void configChanged(ConfigChanged change) {
                services.stopUnwanted();
            }
        };
        config.addListener(reconcile);
        InetSocketAddress channelAddress = network.start();
        services.channelAddress(channelAddress.getAddress().getHostAddress(), channelAddress.getPort());
        startConsole();
        ServiceManager.Recovery recovery = services.recoverOrphans();
        if (!recovery.terminated().isEmpty() || !recovery.unverifiable().isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Left over from the previous run: ended {0}, not verifiable {1}",
                    recovery.terminated(), recovery.unverifiable());
        }
        if (!config.snapshot().cloud().setupComplete() || !config.snapshot().cloud().eulaAccepted()) {
            LOG.log(System.Logger.Level.WARNING, "The setup is not complete (cloud.yml: setup-complete, eula-accepted), "
                    + "so no server is started. See docs/cloud/node.md.");
        } else {
            services.startConfigured();
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

    private void startConsole() {
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
        console.start();
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
        try {
            if (console != null) {
                console.stop();
            }
            if (reconcile != null) {
                config.removeListener(reconcile);
            }
            services.close();
            database.shutdown();
            network.close();
        } finally {
            try {
                config.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Closing the configuration failed", e);
            }
            closeConsole();
            try {
                homeLock.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Releasing the home lock failed", e);
            }
            closed.countDown();
            closeDone.countDown();
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

        private void init() {
            if (providers == null) {
                DownloadClient client = DownloadClient.createDefault();
                providers = ProviderRegistry.standard(client);
                downloader = new ArtifactDownloader(client, providers, new ArtifactCache(home.resolve("cache")));
                // One store for every download of the node: JREs, MariaDB, later the server software.
                lockStore = SoftwareLockStore.inHome(home);
            }
        }
    }
}
