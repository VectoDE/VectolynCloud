package net.vectora.cloud.database;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.secret.RedactingFilter;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.database.sql.Account;
import net.vectora.cloud.database.sql.BootstrapStatements;
import net.vectora.cloud.database.sql.ConnectionTarget;
import net.vectora.cloud.database.sql.ServerVersion;
import net.vectora.cloud.database.sql.SqlConnector;
import net.vectora.cloud.database.sql.SqlQuoting;
import net.vectora.cloud.database.sql.SqlSession;

/**
 * Steps shared by the modes that set a server up themselves (managed and Docker), and the login
 * check every MariaDB mode ends with.
 */
public final class ServerSetup {

    private ServerSetup() {
    }

    /**
     * Secures the server and creates the application database and user, as {@code root}.
     *
     * @param root a session of {@code root}
     * @param database database settings
     * @param applicationHost host part of the application user
     * @param keepRootHosts host parts of {@code root} that stay
     * @param secrets the passwords, for the statements and to keep them out of error messages
     * @throws DatabaseException if a statement fails
     */
    public static void secureAndCreate(SqlSession root, DatabaseConfig database, String applicationHost,
            Set<String> keepRootHosts, DatabaseSecrets secrets) throws DatabaseException {
        RedactingFilter redact = RedactingFilter.of(List.of(secrets.root(), secrets.application()));
        List<Account> accounts = new ArrayList<>();
        try {
            for (List<String> row : root.query(BootstrapStatements.listAccounts())) {
                if (row.size() >= 2) {
                    accounts.add(new Account(row.get(0), row.get(1)));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException(DatabaseException.Reason.SETUP,
                    "Could not list the database accounts: " + describe(e, redact), e);
        }
        List<String> statements = new ArrayList<>();
        try {
            statements.addAll(BootstrapStatements.secure(accounts, keepRootHosts));
        } catch (IllegalArgumentException e) {
            throw new DatabaseException(DatabaseException.Reason.SETUP, "The server lists an account whose name"
                    + " cannot be quoted safely; remove it by hand: " + e.getMessage(), e);
        }
        statements.addAll(BootstrapStatements.application(database.name(), database.user(), applicationHost,
                secrets.application()));
        for (int i = 0; i < statements.size(); i++) {
            try {
                root.execute(statements.get(i));
            } catch (SQLException e) {
                throw new DatabaseException(DatabaseException.Reason.SETUP, "Setup statement " + (i + 1) + " of "
                        + statements.size() + " failed: " + describe(e, redact), e);
            }
        }
    }

    /**
     * Logs in as the application user, selects its database and reads the server version.
     *
     * @param connector the connector
     * @param target where the server is
     * @param database database settings
     * @param password the application password
     * @return the server version, if the server reports a readable one
     * @throws DatabaseException if the login or the database fails
     */
    public static Optional<ServerVersion> checkApplicationLogin(SqlConnector connector, ConnectionTarget target,
            DatabaseConfig database, Secret password) throws DatabaseException {
        RedactingFilter redact = RedactingFilter.of(List.of(password));
        try (SqlSession session = connector.open(target, database.user(), password)) {
            session.execute("USE " + SqlQuoting.identifier(database.name()));
            session.query("SELECT 1");
            return ServerVersion.parse(session.serverVersion());
        } catch (SQLException e) {
            throw new DatabaseException(DatabaseException.Reason.LOGIN, "User '" + database.user()
                    + "' cannot use database '" + database.name() + "' on " + target + ": " + describe(e, redact), e);
        }
    }

    /**
     * Reads {@code @@max_connections} of a server as the given user. Any user may read it.
     *
     * @param connector the connector
     * @param target the server
     * @param user the user
     * @param password the password
     * @return the value, or empty if it cannot be read or is not a positive number
     */
    public static java.util.OptionalInt maxConnections(SqlConnector connector, ConnectionTarget target, String user,
            Secret password) {
        try (SqlSession session = connector.open(target, user, password)) {
            List<List<String>> rows = session.query(BootstrapStatements.maxConnections());
            if (rows.size() != 1 || rows.getFirst().size() != 1) {
                return java.util.OptionalInt.empty();
            }
            String value = rows.getFirst().getFirst();
            if (value == null || !value.matches("[0-9]{1,9}")) {
                return java.util.OptionalInt.empty();
            }
            int max = Integer.parseInt(value);
            return max > 0 ? java.util.OptionalInt.of(max) : java.util.OptionalInt.empty();
        } catch (SQLException e) {
            return java.util.OptionalInt.empty();
        }
    }

    /**
     * Logs in, runs {@code SELECT 1} and reads the version.
     *
     * @param connector the connector
     * @param target where the server is
     * @param user the user
     * @param password the password
     * @return the report; an unreachable server is a report, not an exception
     */
    public static HealthReport health(SqlConnector connector, ConnectionTarget target, String user, Secret password) {
        long start = System.nanoTime();
        try (SqlSession session = connector.open(target, user, password)) {
            session.query("SELECT 1");
            String version = ServerVersion.parse(session.serverVersion()).map(ServerVersion::toString).orElse("unknown");
            return new HealthReport(true, Duration.ofNanos(System.nanoTime() - start), version);
        } catch (SQLException e) {
            return new HealthReport(false, Duration.ofNanos(System.nanoTime() - start),
                    describe(e, RedactingFilter.of(List.of(password))));
        }
    }

    /**
     * Opens a session, retrying until it works or the time is up.
     *
     * @param connector the connector
     * @param target where the server is
     * @param user the user
     * @param password the password
     * @param timeout how long to keep trying
     * @param stillWorthWaiting checked before each attempt; {@code false} stops early (process died)
     * @return the session
     * @throws SQLException the last error if the time ran out
     * @throws InterruptedException if interrupted
     */
    public static SqlSession openWithRetry(SqlConnector connector, ConnectionTarget target, String user, Secret password,
            Duration timeout, java.util.function.BooleanSupplier stillWorthWaiting)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        SQLException last = null;
        while (true) {
            if (!stillWorthWaiting.getAsBoolean()) {
                throw last != null ? last : new SQLException("server process ended");
            }
            try {
                return connector.open(target, user, password);
            } catch (SQLException e) {
                last = e;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw last;
            }
            Thread.sleep(500);
        }
    }

    /**
     * Describes an SQL error without any password in it.
     *
     * @param e the error
     * @param redact the filter for the passwords in play
     * @return SQL state, error code and message
     */
    public static String describe(SQLException e, RedactingFilter redact) {
        String message = e.getMessage() == null ? "" : redact.redact(e.getMessage());
        return message + " (SQL state " + e.getSQLState() + ", error " + e.getErrorCode() + ")";
    }
}
