// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.database.sql.ConnectionTarget;
import net.vectolyn.cloud.database.sql.PostgresConnector;
import net.vectolyn.cloud.database.sql.SqlSession;
import net.vectolyn.cloud.database.sql.TlsMode;
import net.vectolyn.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostgresProvisionerTest {

    private static final Secret APP = Secret.of("PgPasswordForTests12345");

    @TempDir
    Path home;

    /** Answers the rights query with a fixed row and records every login. */
    private static final class FakePostgres implements PostgresConnector {
        List<String> row = List.of("public", "t", "t", "t", "f", "160004", "200");
        SQLException failure;
        final List<String> logins = new ArrayList<>();
        final List<ConnectionTarget> targets = new ArrayList<>();
        final List<String> queries = new ArrayList<>();

        @Override
        public SqlSession open(ConnectionTarget target, String database, String user, Secret password)
                throws SQLException {
            targets.add(target);
            logins.add(user + "@" + database);
            if (failure != null) {
                throw failure;
            }
            return new SqlSession() {
                @Override
                public void execute(String sql) {
                    throw new AssertionError("the cloud changes nothing on a PostgreSQL server: " + sql);
                }

                @Override
                public List<List<String>> query(String sql) {
                    queries.add(sql);
                    return sql.equals(PostgresProvisioner.RIGHTS_QUERY) ? List.of(row) : List.of(List.of(row.get(5)));
                }

                @Override
                public String serverVersion() {
                    return "16.4";
                }

                @Override
                public void close() {
                    // nothing open
                }
            };
        }
    }

    private final FakePostgres server = new FakePostgres();

    private static DatabaseConfig config(String host) {
        return new DatabaseConfig(DatabaseMode.POSTGRESQL, host, 5432, "luckperms", "luckperms", null);
    }

    private PostgresProvisioner provisioner() {
        return new PostgresProvisioner(config("db.example.net"), APP, server);
    }

    @Test
    void checksLoginRightsAndBudget() throws Exception {
        PostgresProvisioner p = provisioner();
        DatabaseEndpoint endpoint = p.provision();
        assertEquals(StorageMethod.POSTGRESQL, endpoint.storageMethod());
        assertEquals("postgresql", endpoint.placeholderValues(4).get(DatabaseEndpoint.STORAGE_METHOD));
        assertEquals(5432, endpoint.port());
        assertEquals(List.of("luckperms@luckperms"), server.logins);
        assertEquals(List.of(PostgresProvisioner.RIGHTS_QUERY), server.queries);
        assertEquals(TlsMode.VERIFY_FULL, server.targets.getFirst().tls(), "remote servers keep TLS verification");
        assertEquals(ConnectionBudget.forServer(200), p.connectionBudget());
        assertTrue(p.notice().isEmpty(), p.notice().toString());
        assertEquals(DatabaseMode.POSTGRESQL, p.mode());
        HealthReport health = p.health();
        assertTrue(health.healthy());
        assertEquals("PostgreSQL 16.4", health.detail());
        p.shutdown();
    }

    @Test
    void missingRightsNameTheGrants() {
        server.row = List.of("lp schema", "f", "t", "f", "f", "170000", "100");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertEquals(DatabaseException.Reason.RIGHTS, e.reason());
        assertTrue(e.getMessage().contains("GRANT CONNECT ON DATABASE \"luckperms\" TO \"luckperms\";"), e.getMessage());
        assertTrue(e.getMessage().contains("GRANT CREATE ON SCHEMA \"lp schema\" TO \"luckperms\";"), e.getMessage());

        server.row = List.of("public", "t", "f", "f", "f", "170000", "100");
        e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertTrue(e.getMessage().contains("GRANT USAGE, CREATE ON SCHEMA \"public\""), e.getMessage());
        assertFalse(e.getMessage().contains("CONNECT"), e.getMessage());

        server.row = List.of("public", "t", "f", "t", "f", "170000", "100");
        e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertTrue(e.getMessage().contains("GRANT USAGE ON SCHEMA \"public\""), e.getMessage());
    }

    @Test
    void schemaNameFromTheServerCannotBreakOut() {
        server.row = List.of("x\"; DROP SCHEMA public; --", "t", "t", "f", "f", "170000", "100");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertTrue(e.getMessage().contains("\"x\"\"; DROP SCHEMA public; --\""), e.getMessage());
        server.row = List.of("bad\nname", "t", "t", "f", "f", "170000", "100");
        e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertFalse(e.getMessage().contains("bad\nname"), e.getMessage());
    }

    @Test
    void noSchemaInTheSearchPath() {
        server.row = List.of("", "t", "f", "f", "f", "170000", "100");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertEquals(DatabaseException.Reason.SETTINGS, e.reason());
        assertTrue(e.getMessage().contains("ALTER ROLE \"luckperms\" SET search_path"), e.getMessage());
    }

    @Test
    void superuserOldServerAndUnknownLimitGiveNotices() throws Exception {
        server.row = List.of("public", "t", "t", "t", "t", "130012", "");
        PostgresProvisioner p = provisioner();
        p.provision();
        String notice = p.notice().orElseThrow();
        assertTrue(notice.contains("superuser"), notice);
        assertTrue(notice.contains("PostgreSQL 13.12"), notice);
        assertTrue(notice.contains("max_connections"), notice);
        assertEquals(ConnectionBudget.managed(), p.connectionBudget());
    }

    @Test
    void tooFewConnectionsFail() {
        server.row = List.of("public", "t", "t", "t", "f", "160004", "1");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertEquals(DatabaseException.Reason.CONNECTION_BUDGET, e.reason());
    }

    @Test
    void unexpectedAnswerIsALoginProblem() {
        server.row = List.of("public", "t");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertEquals(DatabaseException.Reason.LOGIN, e.reason());
    }

    @Test
    void refusedLoginNeverShowsThePassword() {
        server.failure = new SQLException("FATAL: password authentication failed, got " + APP.reveal(), "28P01");
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner().provision());
        assertEquals(DatabaseException.Reason.LOGIN, e.reason());
        assertFalse(e.getMessage().contains(APP.reveal()), e.getMessage());
        HealthReport health = provisioner().health();
        assertFalse(health.healthy());
        assertFalse(health.detail().contains(APP.reveal()), health.detail());
    }

    @Test
    void emptyPasswordAndWrongModeAreRefused() {
        DatabaseException e = assertThrows(DatabaseException.class,
                () -> new PostgresProvisioner(config("db.example.net"), Secret.of(""), server).provision());
        assertEquals(DatabaseException.Reason.SETTINGS, e.reason());
        assertTrue(server.logins.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new PostgresProvisioner(
                new DatabaseConfig(DatabaseMode.EXTERNAL, "db.example.net", 3306, "lp", "lp", null), APP, server));
    }

    @Test
    void versionText() {
        assertEquals("PostgreSQL 16.4", PostgresProvisioner.versionText("160004"));
        assertEquals("PostgreSQL 9.6.24", PostgresProvisioner.versionText("90624"));
        assertEquals("PostgreSQL (unknown version)", PostgresProvisioner.versionText("abc"));
        assertEquals("PostgreSQL (unknown version)", PostgresProvisioner.versionText(null));
        assertEquals(0, PostgresProvisioner.major("99999999"));
        assertEquals(17, PostgresProvisioner.major("170002"));
    }

    @Test
    void factoryPicksPostgres() {
        DatabaseProvisioners.Managed managed = new DatabaseProvisioners.Managed(platform -> home,
                new ProvisionersTest.NoProcesses(),
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64, TargetPlatform.Libc.GLIBC));
        assertInstanceOf(PostgresProvisioner.class, DatabaseProvisioners.create(config("db.example.net"),
                new DatabaseSecrets(Secret.of("RootPasswordForTests0123"), APP), home, new FakeSqlServer(), managed));
    }
}
