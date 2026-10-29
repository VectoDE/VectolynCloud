package net.vectora.cloud.node.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.config.operation.SetServerWanted;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.database.ConnectionBudget;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.database.DatabaseProvisioner;
import net.vectora.cloud.database.HealthReport;
import net.vectora.cloud.database.StorageMethod;
import net.vectora.cloud.node.service.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeDatabaseTest {

    @TempDir
    Path dir;

    /** Counts calls; fails the first provision when asked to. */
    private static final class Provisioner implements DatabaseProvisioner {
        final AtomicInteger provisions = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();
        ConnectionBudget budget = ConnectionBudget.managed();
        int failures;

        @Override
        public DatabaseMode mode() {
            return DatabaseMode.EXTERNAL;
        }

        @Override
        public DatabaseEndpoint provision() throws DatabaseException {
            provisions.incrementAndGet();
            if (failures > 0) {
                failures--;
                throw new DatabaseException(DatabaseException.Reason.UNREACHABLE, "server down");
            }
            return new DatabaseEndpoint(StorageMethod.MARIADB, "db.example", 3306, "vectora", "vectora", Optional.empty());
        }

        @Override
        public HealthReport health() {
            return new HealthReport(true, Duration.ZERO, "ok");
        }

        @Override
        public Optional<String> notice() {
            return Optional.of("max_connections could not be read");
        }

        @Override
        public ConnectionBudget connectionBudget() {
            return budget;
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }

    private ConfigSnapshot config(List<ConfigOperation> operations) throws Exception {
        try (ConfigService config = ConfigService.open(CloudHome.of(dir.resolve("home")))) {
            if (!operations.isEmpty()) {
                config.apply(operations);
            }
            return config.snapshot();
        }
    }

    private static GroupConfig group(ServerKind kind) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, kind.templateFolder(), ServerSoftware.PAPER, "26.3",
                JavaSelection.AUTO, new HeapConfig(MemorySize.parse("16M"), MemorySize.parse("64M")), List.of(),
                CombatSystem.MODERN, null);
    }

    private static PutTask task(String name, int max) {
        return new PutTask(name, new TaskConfig(TaskConfig.SCHEMA_VERSION, name, TaskMode.DYNAMIC,
                new ScalingConfig(1, max, 50, 80, TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of()));
    }

    @Test
    void provisionsOnceLogsTheNoticeAndShutsDownOnce() throws Exception {
        ConfigSnapshot snapshot = config(List.of());
        Provisioner provisioner = new Provisioner();
        provisioner.failures = 1;
        NodeDatabase database = new NodeDatabase(() -> snapshot, () -> EnvFile.empty(), (s, secrets) -> provisioner);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(NodeDatabase.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            assertTrue(database.values(null, snapshot).isEmpty(), "no values before the database is set up");
            ServiceException failed = assertThrows(ServiceException.class, database::beforeStart);
            assertTrue(failed.getMessage().contains("server down"), failed.getMessage());
            assertTrue(database.endpoint().isEmpty());
            database.beforeStart();
            database.beforeStart();
            assertEquals(2, provisioner.provisions.get(), "a failed attempt is repeated, a success is not");
            assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                    && r.getMessage().contains("max_connections could not be read")), "notice logged as warning");
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(Map.of("VECTORA_DB_HOST", "db.example", "VECTORA_DB_PORT", "3306", "VECTORA_DB_NAME", "vectora",
                "VECTORA_DB_USER", "vectora", "VECTORA_DB_STORAGE_METHOD", "mariadb", "VECTORA_DB_POOL_SIZE", "4"),
                database.values(null, snapshot));
        assertTrue(database.health().orElseThrow().healthy());

        database.shutdown();
        database.shutdown();
        assertEquals(1, provisioner.shutdowns.get());
        assertTrue(database.endpoint().isEmpty(), "set up again at the next start");
    }

    @Test
    void thePoolSizeFollowsTheBudgetAndTheWantedTasks() throws Exception {
        ConfigSnapshot snapshot = config(List.of(new PutGroup("lobby", group(ServerKind.LOBBY)), task("lobby", 10),
                new PutGroup("minigame", group(ServerKind.MINIGAME)), task("minigame", 20),
                new PutGroup("survival", group(ServerKind.SURVIVAL)), task("survival", 100),
                new SetServerWanted(ServerKind.SURVIVAL, false)));
        assertEquals(30, NodeDatabase.pooledServices(snapshot), "the unwanted survival task does not count");

        Provisioner provisioner = new Provisioner();
        provisioner.budget = new ConnectionBudget(100, 10); // 90 for 30 services: 3 each
        NodeDatabase database = new NodeDatabase(() -> snapshot, () -> EnvFile.empty(), (s, secrets) -> provisioner);
        database.beforeStart();
        assertEquals("3", database.values(null, snapshot).get(DatabaseEndpoint.POOL_SIZE));

        provisioner.budget = new ConnectionBudget(60, 10); // 50 for 30 services: below the minimum of 2
        NodeDatabase tight = new NodeDatabase(() -> snapshot, () -> EnvFile.empty(), (s, secrets) -> provisioner);
        tight.beforeStart();
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> tight.values(null, snapshot));
        assertTrue(refused.getMessage().contains("max_connections"), refused.getMessage());
    }

    @Test
    void anUnwantedManagedDatabaseStartsNoServerButFallsBackToH2() throws Exception {
        ConfigSnapshot snapshot = config(List.of(new SetServerWanted(ServerKind.DATABASE, false)));
        assertEquals(DatabaseMode.MANAGED, snapshot.cloud().database().mode());
        AtomicInteger created = new AtomicInteger();
        NodeDatabase database = new NodeDatabase(() -> snapshot, () -> EnvFile.empty(), (s, secrets) -> {
            created.incrementAndGet();
            return new Provisioner();
        });
        database.beforeStart();
        assertEquals(0, created.get(), "no MariaDB provisioner for a database that is not wanted");
        assertEquals("h2", database.values(null, snapshot).get(DatabaseEndpoint.STORAGE_METHOD));
    }

    @Test
    void afterTheShutdownNothingIsSetUpAgain() throws Exception {
        ConfigSnapshot snapshot = config(List.of());
        Provisioner provisioner = new Provisioner();
        NodeDatabase database = new NodeDatabase(() -> snapshot, () -> EnvFile.empty(), (s, secrets) -> provisioner);
        database.shutdown();
        ServiceException refused = assertThrows(ServiceException.class, database::beforeStart);
        assertTrue(refused.getMessage().contains("shut down"), refused.getMessage());
        assertEquals(0, provisioner.provisions.get(), "a start racing with the shutdown leaves no database behind");
    }
}
