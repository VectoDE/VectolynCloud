package net.vectolyn.cloud.node.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.vectolyn.cloud.backup.BackupException;
import net.vectolyn.cloud.backup.BackupTarget;
import net.vectolyn.cloud.backup.BackupTargets;
import net.vectolyn.cloud.backup.DatabaseSource;
import net.vectolyn.cloud.backup.ServiceControl;
import net.vectolyn.cloud.backup.database.DatabaseAccess;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.secret.EnvFormatException;
import net.vectolyn.cloud.config.secret.SecretStore;
import net.vectolyn.cloud.database.managed.DownloadingMariaDbBinaries;
import net.vectolyn.cloud.download.provider.TargetPlatform;
import net.vectolyn.cloud.node.service.ServiceException;
import net.vectolyn.cloud.node.service.ServiceIds;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceListener;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.node.service.ServicePlanner;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;

/**
 * What cloud-backup needs from the node ({@code docs/cloud/backup.md#what-cloud-node-provides}):
 * the static services, their control and the database access.
 */
public final class NodeBackups implements BackupTargets, ServiceControl, DatabaseSource {

    private final Path home;
    private final ServiceManager services;
    private final Supplier<ConfigSnapshot> config;
    private final SecretStore secrets;
    private final Path databaseHome;

    /**
     * Creates the adapter.
     *
     * @param home         the cloud home
     * @param services     the service core
     * @param config       the current configuration
     * @param secrets      {@code secrets/vectolyn.env}
     * @param databaseHome {@code <home>/database}, where a managed MariaDB is installed
     */
    public NodeBackups(Path home, ServiceManager services, Supplier<ConfigSnapshot> config, SecretStore secrets,
                       Path databaseHome) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.services = Objects.requireNonNull(services, "services");
        this.config = Objects.requireNonNull(config, "config");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.databaseHome = Objects.requireNonNull(databaseHome, "databaseHome");
    }

    /**
     * Every static service the configuration asks for on this node, wanted or not: a group that is
     * switched off keeps its world, and its backups stay possible. Proxies save no world.
     */
    @Override
    public List<BackupTarget> staticServices() {
        ConfigSnapshot snapshot = config.get();
        String node = snapshot.cloud().nodeId();
        List<BackupTarget> targets = new ArrayList<>();
        for (TaskConfig task : snapshot.tasks().values()) {
            GroupConfig group = snapshot.groups().get(task.group());
            if (group == null || task.scaling() == null || task.scaling().staticServices() == 0
                    || !ServicePlanner.runsOn(task, node)
                    || ServicePlanner.kindOf(group).orElse(null) == ServerKind.DATABASE) {
                continue;
            }
            for (int index = 1; index <= task.scaling().maxServices(); index++) {
                ServiceId id = ServiceIds.of(task.group(), index);
                Path folder = ServicePlanner.staticDirectory(home, id.value());
                // The static services static-services asks for, and any further one that has run before.
                if (index > task.scaling().staticServices() && !Files.isDirectory(folder)) {
                    continue;
                }
                targets.add(new BackupTarget(id.value(), task.group(), group.minecraftVersion(), folder,
                        group.software() != ServerSoftware.VELOCITY));
            }
        }
        targets.sort(java.util.Comparator.comparing(BackupTarget::serviceId));
        return List.copyOf(targets);
    }

    @Override
    public State state(String serviceId) {
        Optional<ServiceInfo> info = id(serviceId).flatMap(services::find);
        if (info.isEmpty()) {
            return State.STOPPED;
        }
        return switch (info.get().state()) {
            case RUNNING -> State.RUNNING;
            // PREPARED is a start in progress: its folder is being rendered.
            case PREPARED, STARTING, STOPPING -> State.BUSY;
            case STOPPED, CRASHED -> State.STOPPED;
        };
    }

    @Override
    public boolean execute(String serviceId, String command, Predicate<String> confirmation, Duration timeout)
            throws IOException, InterruptedException {
        ServiceId id = id(serviceId).orElseThrow(() -> new IOException("not a service id"));
        CountDownLatch confirmed = new CountDownLatch(1);
        ServiceListener listener = new ServiceListener() {
            @Override
            public void stateChanged(ServiceInfo service, ServiceState previous) {
            }

            @Override
            public void output(ServiceId service, String line) {
                if (service.equals(id) && confirmation.test(line)) {
                    confirmed.countDown();
                }
            }
        };
        // Listen first, then send: a fast answer must not be missed.
        services.addListener(listener);
        try {
            try {
                services.sendCommand(id, command);
            } catch (ServiceException e) {
                throw new IOException(e.getMessage(), e);
            }
            return confirmed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            services.removeListener(listener);
        }
    }

    @Override
    public Hold holdStopped(String serviceId) throws BackupException {
        ServiceId id = id(serviceId).orElseThrow(() -> new BackupException(BackupException.Reason.UNKNOWN_SERVICE,
                "not a service id"));
        try {
            ServiceManager.Hold hold = services.holdStopped(id);
            return hold::close;
        } catch (ServiceException e) {
            throw new BackupException(BackupException.Reason.SERVICE_RUNNING, e.getMessage());
        }
    }

    @Override
    public Optional<DatabaseAccess> access() throws IOException {
        ConfigSnapshot snapshot = config.get();
        Path managed = snapshot.cloud().database().mode() == DatabaseMode.MANAGED
                ? DownloadingMariaDbBinaries.installed(databaseHome, TargetPlatform.current()).orElse(null)
                : null;
        try {
            return Optional.of(DatabaseAccess.of(snapshot.cloud().database(), secrets.load(), managed));
        } catch (EnvFormatException e) {
            throw new IOException("secrets/vectolyn.env cannot be read: " + e.getMessage(), e);
        }
    }

    private static Optional<ServiceId> id(String value) {
        try {
            return Optional.of(new ServiceId(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
