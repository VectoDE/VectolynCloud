// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.secret.RedactingFilter;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.database.sql.ConnectionTarget;
import net.vectolyn.cloud.database.sql.PostgresConnector;
import net.vectolyn.cloud.database.sql.PostgresQuoting;
import net.vectolyn.cloud.database.sql.SqlSession;
import org.jspecify.annotations.Nullable;

/**
 * Mode {@code postgresql}: a PostgreSQL server the operator runs. Like {@link ExternalProvisioner},
 * the cloud creates nothing there: it logs in as the application user to its database, checks the
 * rights LuckPerms needs ({@code CONNECT} on the database, {@code USAGE} and {@code CREATE} on the
 * schema it works in), reads the version and {@code max_connections}, and names the missing
 * {@code GRANT} statements if a right is missing.
 */
public final class PostgresProvisioner implements DatabaseProvisioner {

    /** Oldest PostgreSQL release with community support at the time of writing (13 ended 2025-11). */
    public static final int MIN_MAJOR = 14;

    static final String RIGHTS_QUERY = "SELECT current_schema(), "
            + "has_database_privilege(current_database(), 'CONNECT'), "
            + "has_schema_privilege(current_schema(), 'USAGE'), "
            + "has_schema_privilege(current_schema(), 'CREATE'), "
            + "(SELECT rolsuper FROM pg_roles WHERE rolname = current_user), "
            + "current_setting('server_version_num'), "
            + "current_setting('max_connections')";

    private final DatabaseConfig config;
    private final Secret password;
    private final PostgresConnector connector;
    private final ConnectionTarget target;
    private volatile ConnectionBudget budget = ConnectionBudget.managed();
    private volatile Optional<String> notice = Optional.empty();

    /**
     * Creates the provisioner.
     *
     * @param config    database settings, mode {@code postgresql}
     * @param password  the application password ({@code VECTOLYN_DB_PASSWORD})
     * @param connector the connector
     */
    public PostgresProvisioner(DatabaseConfig config, Secret password, PostgresConnector connector) {
        this.config = Objects.requireNonNull(config, "config");
        if (config.mode() != DatabaseMode.POSTGRESQL) {
            throw new IllegalArgumentException("database mode postgresql expected, got " + config.mode());
        }
        this.password = Objects.requireNonNull(password, "password");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.target = ConnectionTarget.of(config.host(), config.port());
    }

    @Override
    public DatabaseMode mode() {
        return DatabaseMode.POSTGRESQL;
    }

    @Override
    public DatabaseEndpoint provision() throws DatabaseException {
        if (password.isEmpty()) {
            throw new DatabaseException(DatabaseException.Reason.SETTINGS, "VECTOLYN_DB_PASSWORD in"
                    + " secrets/vectolyn.env is empty. Enter the password of user '" + config.user()
                    + "' of your PostgreSQL server there.");
        }
        RedactingFilter redact = RedactingFilter.of(List.of(password));
        List<String> row;
        try (SqlSession session = connector.open(target, config.name(), config.user(), password)) {
            List<List<String>> rows = session.query(RIGHTS_QUERY);
            if (rows.size() != 1 || rows.getFirst().size() != 7) {
                throw new DatabaseException(DatabaseException.Reason.LOGIN, "PostgreSQL on " + target
                        + " answered the rights check with an unexpected result.");
            }
            row = rows.getFirst();
        } catch (SQLException e) {
            throw new DatabaseException(DatabaseException.Reason.LOGIN, "User '" + config.user()
                    + "' cannot use database '" + config.name() + "' on " + target + ": "
                    + ServerSetup.describe(e, redact), e);
        }
        String schema = row.get(0);
        if (schema.isEmpty()) {
            throw new DatabaseException(DatabaseException.Reason.SETTINGS, "User '" + config.user()
                    + "' has no schema in its search_path on database '" + config.name() + "'. Set one, for example: "
                    + "ALTER ROLE " + quote(config.user()) + " SET search_path = public;");
        }
        List<String> grants = new ArrayList<>();
        if (!isTrue(row.get(1))) {
            grants.add("GRANT CONNECT ON DATABASE " + quote(config.name()) + " TO " + quote(config.user()) + ";");
        }
        boolean usage = isTrue(row.get(2));
        boolean create = isTrue(row.get(3));
        if (!usage || !create) {
            String rights = !usage && !create ? "USAGE, CREATE" : usage ? "CREATE" : "USAGE";
            grants.add("GRANT " + rights + " ON SCHEMA " + quote(schema) + " TO " + quote(config.user()) + ";");
        }
        if (!grants.isEmpty()) {
            throw new DatabaseException(DatabaseException.Reason.RIGHTS, "User '" + config.user()
                    + "' lacks rights LuckPerms needs on database '" + config.name() + "'. Run as the owner: "
                    + String.join(" ", grants));
        }
        int major = major(row.get(5));
        List<String> notes = new ArrayList<>();
        if (major > 0 && major < MIN_MAJOR) {
            notes.add(versionText(row.get(5)) + " is out of community support; use " + MIN_MAJOR + " or newer.");
        }
        if (isTrue(row.get(4))) {
            notes.add("User '" + config.user() + "' is a superuser. Give the cloud a user with only the rights in "
                    + "docs/cloud/database.md#postgresql.");
        }
        String max = row.get(6);
        if (max.matches("[0-9]{1,9}") && Integer.parseInt(max) > 0) {
            budget = ConnectionBudget.forServer(Integer.parseInt(max));
        } else {
            budget = ConnectionBudget.managed();
            notes.add("The server did not tell its max_connections, so the cloud assumes "
                    + ConnectionBudget.MANAGED_MAX_CONNECTIONS + ".");
        }
        notice = notes.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", notes));
        // ServerVersion describes MariaDB and MySQL; the PostgreSQL version goes to the log and health only.
        return new DatabaseEndpoint(StorageMethod.POSTGRESQL, config.host(), config.port(), config.name(),
                config.user(), Optional.empty());
    }

    /**
     * The major version from {@code server_version_num} ({@code 160004} is 16).
     *
     * @param number the setting, or {@code null} if the server did not tell it
     * @return the major version, or 0 if the text is no version number
     */
    static int major(@Nullable String number) {
        return number != null && number.matches("[1-9][0-9]{4,6}") ? Integer.parseInt(number) / 10000 : 0;
    }

    /**
     * {@code server_version_num} as text: {@code 160004} is {@code PostgreSQL 16.4}.
     *
     * @param number the setting, or {@code null} if the server did not tell it
     * @return the text, {@code PostgreSQL (unknown version)} for anything else
     */
    static String versionText(@Nullable String number) {
        int major = major(number);
        if (major == 0) {
            return "PostgreSQL (unknown version)";
        }
        int value = Integer.parseInt(number);
        // Before 10 the number is major, minor and patch with two digits each (90624 is 9.6.24).
        return major >= 10 ? "PostgreSQL " + major + "." + value % 10000
                : "PostgreSQL " + major + "." + value / 100 % 100 + "." + value % 100;
    }

    private static boolean isTrue(String value) {
        return value.equals("t") || value.equalsIgnoreCase("true");
    }

    private static String quote(String name) {
        try {
            return PostgresQuoting.identifier(name);
        } catch (IllegalArgumentException e) {
            return "<" + e.getMessage() + ">";
        }
    }

    @Override
    public ConnectionBudget connectionBudget() {
        return budget;
    }

    @Override
    public Optional<String> notice() {
        return notice;
    }

    @Override
    public HealthReport health() {
        long start = System.nanoTime();
        try (SqlSession session = connector.open(target, config.name(), config.user(), password)) {
            List<List<String>> rows = session.query("SELECT current_setting('server_version_num')");
            String version = rows.size() == 1 && rows.getFirst().size() == 1
                    ? versionText(rows.getFirst().getFirst()) : versionText(null);
            return new HealthReport(true, Duration.ofNanos(System.nanoTime() - start), version);
        } catch (SQLException e) {
            return new HealthReport(false, Duration.ofNanos(System.nanoTime() - start),
                    ServerSetup.describe(e, RedactingFilter.of(List.of(password))));
        }
    }

    @Override
    public void shutdown() {
        // The operator runs the server.
    }
}
