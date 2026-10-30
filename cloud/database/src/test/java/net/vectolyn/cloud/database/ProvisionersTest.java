package net.vectolyn.cloud.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.database.docker.DockerProvisioner;
import net.vectolyn.cloud.database.managed.ManagedMariaDbProvisioner;
import net.vectolyn.cloud.database.sql.TlsMode;
import net.vectolyn.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** External, embedded and the factory. */
class ProvisionersTest {

    private static final Secret APP = Secret.of("AppPasswordForTests45678");
    private static final DatabaseSecrets SECRETS = new DatabaseSecrets(Secret.of("RootPasswordForTests0123"), APP);

    @TempDir
    Path home;

    private final FakeSqlServer server = new FakeSqlServer();

    private static DatabaseConfig config(DatabaseMode mode, String host) {
        return new DatabaseConfig(mode, host, 3306, "luckperms", "luckperms",
                mode == DatabaseMode.DOCKER ? DatabaseConfig.DEFAULT_DOCKER_IMAGE : null);
    }

    @Test
    void externalChecksTheApplicationLoginOnly() throws Exception {
        server.passwords.put("luckperms", APP.reveal());
        ExternalProvisioner p = new ExternalProvisioner(config(DatabaseMode.EXTERNAL, "db.example.net"), APP, server);
        DatabaseEndpoint endpoint = p.provision();
        assertEquals(StorageMethod.MARIADB, endpoint.storageMethod());
        assertEquals("db.example.net", endpoint.host());
        assertEquals(List.of("USE `luckperms`", "SELECT 1", "SELECT @@max_connections"),
                server.statementsOf("luckperms"));
        assertTrue(server.statementsOf("root").isEmpty(), "never root on an external server");
        assertEquals(TlsMode.VERIFY_FULL, server.targets.get(0).tls(), "remote servers keep TLS verification");
        assertTrue(p.health().healthy());
        p.shutdown();
    }

    @Test
    void externalReadsMaxConnectionsForTheConnectionBudget() throws Exception {
        server.passwords.put("luckperms", APP.reveal());
        server.maxConnections = "500";
        ExternalProvisioner p = new ExternalProvisioner(config(DatabaseMode.EXTERNAL, "db.example.net"), APP, server);
        assertEquals(ConnectionBudget.managed(), p.connectionBudget(), "before provision() the default applies");
        p.provision();
        assertEquals(new ConnectionBudget(500, ConnectionBudget.DEFAULT_RESERVED), p.connectionBudget());
        assertTrue(p.notice().isEmpty());

        server.maxConnections = "10";
        p.provision();
        assertEquals(new ConnectionBudget(10, 2), p.connectionBudget(), "a fifth stays free on a small server");

        server.maxConnections = "1";
        DatabaseException tooSmall = assertThrows(DatabaseException.class, p::provision);
        assertEquals(DatabaseException.Reason.CONNECTION_BUDGET, tooSmall.reason());

        for (String unreadable : new String[] {null, "many", "-1", "0"}) {
            server.maxConnections = unreadable;
            p.provision();
            assertEquals(ConnectionBudget.managed(), p.connectionBudget(), "unreadable value " + unreadable);
            assertTrue(p.notice().orElseThrow().contains("max_connections"), "the operator is told");
        }
    }

    @Test
    void externalMySqlBecomesStorageMethodMysql() throws Exception {
        server.passwords.put("luckperms", APP.reveal());
        server.version = "8.4.3";
        DatabaseEndpoint endpoint = new ExternalProvisioner(config(DatabaseMode.EXTERNAL, "db.example.net"), APP, server)
                .provision();
        assertEquals(StorageMethod.MYSQL, endpoint.storageMethod());
    }

    @Test
    void externalWrongPasswordIsALoginErrorWithoutThePassword() {
        server.passwords.put("luckperms", "SomethingElseEntirely123");
        DatabaseException e = assertThrows(DatabaseException.class,
                () -> new ExternalProvisioner(config(DatabaseMode.EXTERNAL, "db.example.net"), APP, server).provision());
        assertEquals(DatabaseException.Reason.LOGIN, e.reason());
        assertFalse(e.getMessage().contains(APP.reveal()));
    }

    @Test
    void externalWithEmptyPasswordAsksForIt() {
        DatabaseException e = assertThrows(DatabaseException.class, () -> new ExternalProvisioner(
                config(DatabaseMode.EXTERNAL, "db.example.net"), Secret.of(""), server).provision());
        assertEquals(DatabaseException.Reason.SETTINGS, e.reason());
        assertEquals(0, server.attempts);
    }

    @Test
    void externalHealthReportsAnUnreachableServer() {
        server.up = false;
        HealthReport report = new ExternalProvisioner(config(DatabaseMode.EXTERNAL, "db.example.net"), APP, server)
                .health();
        assertFalse(report.healthy());
        assertTrue(report.detail().contains("08000"), report.detail());
    }

    @Test
    void embeddedNeedsNothing() throws Exception {
        EmbeddedProvisioner p = new EmbeddedProvisioner(config(DatabaseMode.H2, "127.0.0.1"));
        DatabaseEndpoint endpoint = p.provision();
        assertEquals(StorageMethod.H2, endpoint.storageMethod());
        assertEquals("h2", endpoint.placeholderValues(4).get("VECTOLYN_DB_STORAGE_METHOD"));
        assertTrue(p.health().healthy());
        p.shutdown();
    }

    @Test
    void wrongModeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddedProvisioner(config(DatabaseMode.EXTERNAL, "127.0.0.1")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalProvisioner(config(DatabaseMode.H2, "127.0.0.1"), APP, server));
    }

    @Test
    void factoryPicksTheMode() {
        DatabaseProvisioners.Managed managed = new DatabaseProvisioners.Managed(platform -> home, new NoProcesses(),
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64, TargetPlatform.Libc.GLIBC));
        assertInstanceOf(ManagedMariaDbProvisioner.class, DatabaseProvisioners.create(
                config(DatabaseMode.MANAGED, "127.0.0.1"), SECRETS, home, server, managed));
        assertInstanceOf(DockerProvisioner.class, DatabaseProvisioners.create(
                config(DatabaseMode.DOCKER, "127.0.0.1"), SECRETS, home, server, managed));
        assertInstanceOf(ExternalProvisioner.class, DatabaseProvisioners.create(
                config(DatabaseMode.EXTERNAL, "db.example.net"), SECRETS, home, server, managed));
        assertInstanceOf(EmbeddedProvisioner.class, DatabaseProvisioners.create(
                config(DatabaseMode.H2, "127.0.0.1"), SECRETS, home, server, managed));
        assertFalse(SECRETS.toString().contains(APP.reveal()));
    }

    @Test
    void managedOnAPlatformWithoutMariaDbBuildFallsBackToH2() throws Exception {
        for (TargetPlatform system : List.of(
                new TargetPlatform(TargetPlatform.Os.MAC, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.NONE),
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.GLIBC),
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64, TargetPlatform.Libc.MUSL))) {
            DatabaseProvisioner p = DatabaseProvisioners.create(config(DatabaseMode.MANAGED, "127.0.0.1"), SECRETS,
                    home, server, new DatabaseProvisioners.Managed(platform -> home, new NoProcesses(), system));
            assertInstanceOf(EmbeddedProvisioner.class, p, system.toString());
            DatabaseEndpoint endpoint = p.provision();
            assertEquals(StorageMethod.H2, endpoint.storageMethod());
            assertEquals("luckperms", endpoint.database());
            String notice = p.notice().orElseThrow();
            assertTrue(notice.contains(system.toString()) && notice.contains("'external'"), notice);
            assertFalse(notice.startsWith("Not") || notice.startsWith("No"), notice);
            assertTrue(p.health().healthy());
        }
        assertEquals(0, server.attempts, "the fallback connects to nothing");
        assertTrue(new EmbeddedProvisioner(config(DatabaseMode.H2, "127.0.0.1")).notice().isEmpty());
    }

    /** Fails every call; for code paths that must not start anything. */
    static final class NoProcesses implements DatabaseProcesses {
        @Override
        public int run(Command command, java.time.Duration timeout) {
            throw new AssertionError("no process expected: " + command.arguments());
        }

        @Override
        public Running start(Command command) {
            throw new AssertionError("no process expected: " + command.arguments());
        }
    }
}
