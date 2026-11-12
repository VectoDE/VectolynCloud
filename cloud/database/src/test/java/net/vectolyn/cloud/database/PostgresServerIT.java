// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.database.sql.ConnectionTarget;
import net.vectolyn.cloud.database.sql.PostgresConnector;
import net.vectolyn.cloud.database.sql.PostgresJdbcConnector;
import net.vectolyn.cloud.database.sql.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link PostgresProvisioner} against a real PostgreSQL server. Tagged {@code postgres}: runs
 * only through {@code ./gradlew :cloud:database:postgresTest}, and only when
 * {@code VECTOLYN_TEST_POSTGRES_URL} names a throwaway server with a superuser, for example
 * {@code postgresql://postgres:secret@localhost:5432/postgres}. The workflow
 * {@code .github/workflows/postgres-real-server.yml} starts one as a service container.
 *
 * <p>Each test creates its own role and database with random names and drops both afterwards, so
 * a shared server stays clean. The superuser only prepares the stage, as an operator would; the
 * adapter itself logs in as the new, unprivileged role.
 */
@Tag("postgres")
class PostgresServerIT {

    private static final String ENV = "VECTOLYN_TEST_POSTGRES_URL";

    private final PostgresConnector connector = new PostgresJdbcConnector();

    private ConnectionTarget target;
    private String adminUser;
    private Secret adminPassword;
    private String adminDatabase;
    private String role;
    private String database;
    private Secret rolePassword;

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv(ENV);
        assumeTrue(url != null && !url.isBlank(), ENV + " is not set; no PostgreSQL server to test against");
        URI uri = URI.create(url);
        assertEquals("postgresql", uri.getScheme(), ENV + " must start with postgresql://");
        String[] credentials = uri.getUserInfo().split(":", 2);
        adminUser = credentials[0];
        adminPassword = Secret.of(credentials.length > 1 ? credentials[1] : "");
        adminDatabase = uri.getPath().length() > 1 ? uri.getPath().substring(1) : "postgres";
        target = ConnectionTarget.of(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 5432);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        role = "vl_it_" + suffix;
        database = "vl_it_" + suffix;
        rolePassword = Secret.of("It-" + UUID.randomUUID() + "-Pw");
        try (SqlSession admin = connector.open(target, adminDatabase, adminUser, adminPassword)) {
            // The owner is the superuser, so the role starts without any right in the new database.
            admin.execute("CREATE ROLE \"" + role + "\" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"
                    + rolePassword.reveal() + "'");
            admin.execute("CREATE DATABASE \"" + database + "\"");
            admin.execute("REVOKE ALL ON DATABASE \"" + database + "\" FROM PUBLIC");
        }
        try (SqlSession inDatabase = connector.open(target, database, adminUser, adminPassword)) {
            // PostgreSQL 15 and newer already do this; older servers let everyone create in public.
            inDatabase.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (role == null || target == null) {
            return;
        }
        try (SqlSession admin = connector.open(target, adminDatabase, adminUser, adminPassword)) {
            admin.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
            admin.execute("DROP ROLE IF EXISTS \"" + role + "\"");
        }
    }

    private PostgresProvisioner provisioner(String user, Secret password) {
        return new PostgresProvisioner(
                new DatabaseConfig(DatabaseMode.POSTGRESQL, target.host(), target.port(), database, user, null),
                password, connector);
    }

    private void grantAsAdmin(String sql) throws SQLException {
        try (SqlSession admin = connector.open(target, database, adminUser, adminPassword)) {
            admin.execute(sql);
        }
    }

    @Test
    void namesTheMissingGrantsAndAcceptsThemAfterwards() throws Exception {
        PostgresProvisioner p = provisioner(role, rolePassword);

        // A fresh database: without CONNECT the role cannot even log in, so the first answer is a login error.
        DatabaseException denied = assertThrows(DatabaseException.class, p::provision);
        assertEquals(DatabaseException.Reason.LOGIN, denied.reason(), denied.getMessage());
        assertFalse(denied.getMessage().contains(rolePassword.reveal()), "the password must not reach the message");

        grantAsAdmin("GRANT CONNECT ON DATABASE \"" + database + "\" TO \"" + role + "\"");
        grantAsAdmin("GRANT USAGE ON SCHEMA public TO \"" + role + "\"");
        DatabaseException noCreate = assertThrows(DatabaseException.class, p::provision);
        assertEquals(DatabaseException.Reason.RIGHTS, noCreate.reason(), noCreate.getMessage());
        String statement = "GRANT CREATE ON SCHEMA \"public\" TO \"" + role + "\";";
        assertTrue(noCreate.getMessage().contains(statement), noCreate.getMessage());

        // The statement from the message is exactly what the operator runs.
        grantAsAdmin(statement.substring(0, statement.length() - 1));
        DatabaseEndpoint endpoint = p.provision();
        assertEquals(StorageMethod.POSTGRESQL, endpoint.storageMethod());
        assertEquals(database, endpoint.database());
        assertEquals(role, endpoint.user());
        assertEquals(target.port(), endpoint.port());
        assertTrue(p.notice().isEmpty(), p.notice().toString());

        HealthReport health = p.health();
        assertTrue(health.healthy(), health.detail());
        assertTrue(health.detail().startsWith("PostgreSQL "), health.detail());
        p.shutdown();
    }

    @Test
    void theCreatedUserWorksWithTheDatabaseAndNothingElse() throws Exception {
        grantAsAdmin("GRANT CONNECT ON DATABASE \"" + database + "\" TO \"" + role + "\"");
        grantAsAdmin("GRANT USAGE, CREATE ON SCHEMA public TO \"" + role + "\"");
        PostgresProvisioner p = provisioner(role, rolePassword);
        DatabaseEndpoint endpoint = p.provision();
        assertTrue(p.notice().isEmpty(), p.notice().toString());

        // What LuckPerms does with the endpoint: log in as the user and own tables in the schema.
        try (SqlSession session = connector.open(target, endpoint.database(), endpoint.user(), rolePassword)) {
            session.execute("CREATE TABLE luckperms_probe (id integer PRIMARY KEY, name text NOT NULL)");
            session.execute("INSERT INTO luckperms_probe VALUES (1, 'vectolyn')");
            assertEquals(List.of(List.of("1", "vectolyn")), session.query("SELECT id, name FROM luckperms_probe"));
            assertEquals(List.of(List.of(role)), session.query("SELECT current_user"));
            assertEquals(List.of(List.of("f")),
                    session.query("SELECT rolsuper FROM pg_roles WHERE rolname = current_user"));
            assertThrows(SQLException.class, () -> session.execute("CREATE DATABASE vl_it_forbidden"));
            assertThrows(SQLException.class, () -> session.execute("CREATE ROLE vl_it_forbidden"));
        }
    }

    @Test
    void wrongPasswordIsALoginErrorWithoutThePassword() {
        Secret wrong = Secret.of("Wrong-" + UUID.randomUUID());
        DatabaseException e = assertThrows(DatabaseException.class, () -> provisioner(role, wrong).provision());
        assertEquals(DatabaseException.Reason.LOGIN, e.reason(), e.getMessage());
        assertFalse(e.getMessage().contains(wrong.reveal()), e.getMessage());
        assertFalse(e.getMessage().contains(rolePassword.reveal()), e.getMessage());

        HealthReport health = provisioner(role, wrong).health();
        assertFalse(health.healthy());
        assertFalse(health.detail().contains(wrong.reveal()), health.detail());
    }

    @Test
    void aSuperuserWorksButGetsANotice() throws Exception {
        PostgresProvisioner p = provisioner(adminUser, adminPassword);
        p.provision();
        String notice = p.notice().orElseThrow();
        assertTrue(notice.contains("superuser"), notice);
    }

    @Test
    void readsMaxConnectionsFromTheServer() throws Exception {
        grantAsAdmin("GRANT CONNECT ON DATABASE \"" + database + "\" TO \"" + role + "\"");
        grantAsAdmin("GRANT USAGE, CREATE ON SCHEMA public TO \"" + role + "\"");
        PostgresProvisioner p = provisioner(role, rolePassword);
        p.provision();
        int max;
        try (SqlSession admin = connector.open(target, adminDatabase, adminUser, adminPassword)) {
            max = Integer.parseInt(admin.query("SHOW max_connections").getFirst().getFirst());
        }
        assertEquals(ConnectionBudget.forServer(max), p.connectionBudget());
    }
}
