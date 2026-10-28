package net.vectora.cloud.node;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.node.service.ServiceManager;

/**
 * One running node: configuration, secrets and the service core, plus the thread that keeps the
 * JVM alive until the node is closed.
 *
 * <p>Start order in {@link #start()}:
 * <ol>
 *   <li>open the configuration (writes the default {@code cloud.yml} and {@code nodes.yml} on the
 *       first start) and watch it for hand edits;</li>
 *   <li>generate missing secrets in {@code secrets/vectora.env};</li>
 *   <li>deal with processes left over from the previous run ({@link ServiceManager#recoverOrphans()});</li>
 *   <li>if the setup is complete and the EULA accepted, start the configured services.</li>
 * </ol>
 * Until the setup assistant exists (stage 2 PR 11) a node without a completed setup only prints
 * how to finish it and starts nothing.
 */
public final class CloudNode implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CloudNode.class.getName());

    private final NodeSettings settings;
    private final ConfigService config;
    private final SecretStore secrets;
    private final ServiceManager services;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Object closeLock = new Object();
    private boolean closing;

    private CloudNode(NodeSettings settings, ConfigService config, SecretStore secrets, ServiceManager services) {
        this.settings = settings;
        this.config = config;
        this.secrets = secrets;
        this.services = services;
    }

    /**
     * Opens configuration and secrets of a home and builds the service core. Nothing is started yet.
     *
     * @param settings launcher settings
     * @param customize hook to replace seams of the service core (JRE resolver, downloads, extra
     *                  placeholders) before it is built
     * @return the node
     * @throws IOException     if the home cannot be read or written
     * @throws ConfigException if the configuration is invalid
     * @throws EnvFormatException if {@code secrets/vectora.env} is malformed
     */
    public static CloudNode open(NodeSettings settings, java.util.function.Consumer<ServiceManager.Builder> customize)
            throws IOException, ConfigException, EnvFormatException {
        Objects.requireNonNull(settings, "settings");
        CloudHome home = CloudHome.of(settings.home());
        ConfigService config = ConfigService.open(home);
        try {
            SecretStore secrets = new SecretStore(home);
            secrets.ensureGenerated(new SecretGenerator());
            ServiceManager.Builder builder = ServiceManager.builder(settings.home(), config::snapshot, secrets::load);
            customize.accept(builder);
            return new CloudNode(settings, config, secrets, builder.build());
        } catch (IOException | EnvFormatException | RuntimeException e) {
            config.close();
            throw e;
        }
    }

    /**
     * Runs the start order described above and starts the thread that keeps the JVM alive.
     *
     * @throws IOException if watching the configuration cannot start
     */
    public void start() throws IOException {
        config.startWatching();
        ServiceManager.Recovery recovery = services.recoverOrphans();
        if (!recovery.terminated().isEmpty() || !recovery.unverifiable().isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Left over from the previous run: ended {0}, not verifiable {1}",
                    recovery.terminated(), recovery.unverifiable());
        }
        CloudConfig cloud = config.snapshot().cloud();
        if (cloud.setupComplete() && cloud.eulaAccepted()) {
            services.startConfigured();
        } else {
            LOG.log(System.Logger.Level.WARNING, "The setup is not complete (cloud.yml: setup-complete, eula-accepted), "
                    + "so no server is started. See docs/cloud/node.md.");
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
     * Stops every service, closes the configuration and lets the JVM end. Safe to call more than
     * once and from a shutdown hook.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (closing) {
                return;
            }
            closing = true;
        }
        try {
            services.close();
        } finally {
            try {
                config.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Closing the configuration failed", e);
            }
            closed.countDown();
        }
    }
}
