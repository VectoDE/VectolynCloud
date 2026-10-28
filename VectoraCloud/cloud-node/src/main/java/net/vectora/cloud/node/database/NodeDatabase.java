package net.vectora.cloud.node.database;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.database.ConnectionBudget;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.database.DatabaseProvisioner;
import net.vectora.cloud.database.DatabaseSecrets;
import net.vectora.cloud.database.HealthReport;
import net.vectora.cloud.node.service.PlaceholderContributor;
import net.vectora.cloud.node.service.ServiceException;
import net.vectora.cloud.node.service.ServiceManager;
import net.vectora.cloud.node.service.ServicePlanner;
import net.vectora.cloud.node.service.ServiceSpec;

/**
 * The network's database as the node runs it.
 *
 * <ul>
 *   <li>As the service core's {@link ServiceManager.StartGate} it calls
 *       {@link DatabaseProvisioner#provision()} before the first service starts, once per run of the
 *       node (a failed attempt is repeated at the next start). The provisioner's
 *       {@link DatabaseProvisioner#notice()} is logged as a warning.</li>
 *   <li>As a {@link PlaceholderContributor} it adds the endpoint's values for the templates:
 *       {@code VECTORA_DB_STORAGE_METHOD} and {@code VECTORA_DB_POOL_SIZE}. The pool size comes
 *       from the provisioner's {@link ConnectionBudget}, shared by every service that may run on
 *       this node at the same time (the sum of {@code max-services} of the wanted tasks).</li>
 *   <li>{@link #shutdown()} stops what the provisioner started, after the last service stopped.</li>
 * </ul>
 * The database mode is read when the database is set up; a change of {@code database.mode}
 * takes effect at the next start of the cloud.
 */
public final class NodeDatabase implements ServiceManager.StartGate, PlaceholderContributor {

    private static final System.Logger LOG = System.getLogger(NodeDatabase.class.getName());

    /** Creates the provisioner for the configured mode. */
    @FunctionalInterface
    public interface Factory {

        /**
         * Creates the provisioner.
         *
         * @param snapshot the configuration
         * @param secrets the two database passwords
         * @return the provisioner of {@code database.mode}
         */
        DatabaseProvisioner create(ConfigSnapshot snapshot, DatabaseSecrets secrets);
    }

    private final Supplier<ConfigSnapshot> config;
    private final ServiceManager.SecretSource secrets;
    private final Factory factory;
    private final Object lock = new Object();
    private DatabaseProvisioner provisioner;
    private volatile DatabaseEndpoint endpoint;
    private volatile ConnectionBudget budget = ConnectionBudget.managed();

    /**
     * Creates the database part of a node; nothing is set up yet.
     *
     * @param config the configuration
     * @param secrets the secrets file
     * @param factory creates the provisioner, normally {@code DatabaseProvisioners.create}
     */
    public NodeDatabase(Supplier<ConfigSnapshot> config, ServiceManager.SecretSource secrets, Factory factory) {
        this.config = Objects.requireNonNull(config, "config");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Reads the two database passwords from the secrets file.
     *
     * @param env {@code secrets/vectora.env}
     * @return the passwords; a missing one is empty
     */
    public static DatabaseSecrets secrets(EnvFile env) {
        return new DatabaseSecrets(env.get(GeneratedSecret.DB_ROOT_PASSWORD.key()).orElse(Secret.of("")),
                env.get(GeneratedSecret.DB_PASSWORD.key()).orElse(Secret.of("")));
    }

    /**
     * Sets the database up if that has not happened in this run yet.
     *
     * @throws ServiceException if it cannot be set up; the message says why and what to do
     */
    @Override
    public void beforeStart() throws ServiceException {
        synchronized (lock) {
            if (endpoint != null) {
                return;
            }
            ConfigSnapshot snapshot = config.get();
            try {
                if (provisioner == null) {
                    provisioner = factory.create(snapshot, secrets(secrets.load()));
                }
                LOG.log(System.Logger.Level.INFO, "Setting up the database (mode {0})",
                        snapshot.cloud().database().mode().name().toLowerCase(java.util.Locale.ROOT));
                DatabaseEndpoint ready = provisioner.provision();
                provisioner.notice().ifPresent(notice -> LOG.log(System.Logger.Level.WARNING, notice));
                budget = provisioner.connectionBudget();
                endpoint = ready;
                LOG.log(System.Logger.Level.INFO, "Database ready: {0} on {1}:{2}",
                        ready.storageMethod().placeholderValue(), ready.host(), Integer.toString(ready.port()));
            } catch (DatabaseException e) {
                throw new ServiceException("the database is not ready: " + e.getMessage(), e);
            } catch (IOException | EnvFormatException e) {
                throw new ServiceException("the database passwords cannot be read from secrets/vectora.env", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("interrupted while setting up the database", e);
            }
        }
    }

    @Override
    public Map<String, String> values(ServiceSpec service, ConfigSnapshot snapshot) {
        DatabaseEndpoint ready = endpoint;
        if (ready == null) {
            return Map.of();
        }
        try {
            return ready.placeholderValues(budget.poolSizeFor(pooledServices(snapshot)));
        } catch (DatabaseException e) {
            // Refuses the start with the budget's explanation, instead of servers that fail later
            // with "Too many connections".
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * How many services with a database pool may run on this node at the same time: the sum of
     * {@code max-services} of every wanted task that runs here.
     *
     * @param snapshot the configuration
     * @return the number
     */
    public static int pooledServices(ConfigSnapshot snapshot) {
        String node = snapshot.cloud().nodeId();
        long total = 0;
        for (TaskConfig task : snapshot.tasks().values()) {
            GroupConfig group = snapshot.groups().get(task.group());
            if (group == null || ServicePlanner.kindOf(group).orElse(null) == ServerKind.DATABASE
                    || !ServicePlanner.isWanted(group, snapshot.cloud()) || !ServicePlanner.runsOn(task, node)) {
                continue;
            }
            total += task.scaling().maxServices();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Returns where the servers find the database, once it is set up.
     *
     * @return the endpoint, or empty before the first start
     */
    public Optional<DatabaseEndpoint> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    /**
     * Checks whether the database answers.
     *
     * @return the report, or empty while the database is not set up
     */
    public Optional<HealthReport> health() {
        DatabaseProvisioner current;
        synchronized (lock) {
            current = endpoint == null ? null : provisioner;
        }
        return current == null ? Optional.empty() : Optional.of(current.health());
    }

    /**
     * Stops what the provisioner started (the managed MariaDB). Call after the last service has
     * stopped. Safe to call more than once and before anything was set up.
     */
    public void shutdown() {
        DatabaseProvisioner current;
        synchronized (lock) {
            current = provisioner;
            provisioner = null;
            endpoint = null;
        }
        if (current == null) {
            return;
        }
        try {
            current.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(System.Logger.Level.WARNING, "Interrupted while stopping the database");
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Stopping the database failed", e);
        }
    }
}
