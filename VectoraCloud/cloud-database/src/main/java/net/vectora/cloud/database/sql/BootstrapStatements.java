package net.vectora.cloud.database.sql;

import static net.vectora.cloud.database.sql.SqlQuoting.account;
import static net.vectora.cloud.database.sql.SqlQuoting.existingAccount;
import static net.vectora.cloud.database.sql.SqlQuoting.identifier;
import static net.vectora.cloud.database.sql.SqlQuoting.literal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.vectora.cloud.config.secret.Secret;

/**
 * The SQL the cloud runs to set up a MariaDB server. Every statement is idempotent, so the setup
 * runs on every start and repairs a server that someone changed by hand.
 *
 * <p>The returned strings contain passwords. Never log them.
 */
public final class BootstrapStatements {

    /**
     * The grants of the {@code test} database and of every {@code test_...} database, as
     * {@code mysql_secure_installation} removes them. The stored name is {@code test\_%} with a
     * literal backslash. It is compared by its bytes ({@code HEX(Db)}, {@code 74 65 73 74 5C 5F 25})
     * instead of a backslash escape, so the condition means the same on a server with
     * {@code NO_BACKSLASH_ESCAPES} in its SQL mode, and no character set or collation of the
     * {@code utf8mb3} column {@code mysql.db.Db} gets in the way.
     */
    static final String TEST_DATABASES = "Db = 'test' OR HEX(Db) = '746573745C5F25'";

    /**
     * Rights of the application user on its own database, nothing else. LuckPerms creates its
     * tables and indexes itself ({@code CREATE}, {@code INDEX}) and works with plain row statements.
     * {@code ALTER} stays for schema migrations of later LuckPerms versions and the Vectora plugin.
     * Left out on purpose: {@code DROP} (would allow {@code DROP DATABASE} and {@code TRUNCATE} on
     * the own data; LuckPerms never drops a table), {@code REFERENCES} (no foreign keys),
     * {@code GRANT OPTION}, routines, triggers, events, locks and every global right.
     */
    public static final List<String> APPLICATION_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "INDEX");

    /** Host parts of {@code root} that stay: local connections only. */
    public static final Set<String> LOCAL_ROOT_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private BootstrapStatements() {
    }

    /**
     * Content of the {@code --init-file} for the first start of a fresh data directory. The server
     * runs it before it accepts any connection, so there is no moment in which {@code root} has no
     * password or an anonymous account exists. One statement per line, as the server expects.
     *
     * <ul>
     *   <li>{@code root} gets the generated password on {@code localhost} and {@code ::1}, and an
     *       account on {@code 127.0.0.1} is created: with {@code skip-name-resolve} a TCP connection
     *       from 127.0.0.1 does not match {@code localhost}.</li>
     *   <li>Anonymous accounts, {@code root} on any other host, and the {@code test} database
     *       (with its open grants) are removed.</li>
     * </ul>
     *
     * @param rootPassword the generated root password
     * @return the file content, ending with a line break
     */
    public static String initFile(Secret rootPassword) {
        String password = literal(rootPassword.reveal());
        List<String> lines = List.of(
                "ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY " + password + ";",
                "ALTER USER IF EXISTS 'root'@'::1' IDENTIFIED BY " + password + ";",
                "CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY " + password + ";",
                "ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY " + password + ";",
                "GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;",
                // mysql.global_priv holds the accounts since MariaDB 10.4; the managed server is 11.x.
                "DELETE FROM mysql.global_priv WHERE User = '';",
                "DELETE FROM mysql.global_priv WHERE User = 'root' AND Host NOT IN ('localhost', '127.0.0.1', '::1');",
                "DROP DATABASE IF EXISTS `test`;",
                "DELETE FROM mysql.db WHERE " + TEST_DATABASES + ";",
                "FLUSH PRIVILEGES;");
        return String.join("\n", lines) + "\n";
    }

    /**
     * What {@code mysql_secure_installation} removes, for a server that is already running: anonymous
     * accounts, {@code root} on hosts other than {@code keepRootHosts}, and the {@code test} database.
     *
     * @param accounts the accounts the server lists ({@code SELECT User, Host FROM mysql.user})
     * @param keepRootHosts host parts of {@code root} that stay, compared case-insensitively
     * @return the statements, possibly only the database cleanup
     */
    public static List<String> secure(Collection<Account> accounts, Set<String> keepRootHosts) {
        Objects.requireNonNull(keepRootHosts, "keepRootHosts");
        Set<String> keep = keepRootHosts.stream().map(h -> h.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> statements = new ArrayList<>();
        for (Account a : accounts) {
            boolean remoteRoot = a.user().equals("root") && !keep.contains(a.host().toLowerCase(Locale.ROOT));
            if (a.anonymous() || remoteRoot) {
                statements.add("DROP USER IF EXISTS " + existingAccount(a.user(), a.host()));
            }
        }
        statements.add("DROP DATABASE IF EXISTS `test`");
        statements.add("DELETE FROM mysql.db WHERE " + TEST_DATABASES);
        statements.add("FLUSH PRIVILEGES");
        return List.copyOf(statements);
    }

    /**
     * Creates the application database and its user, or brings both back to the wanted state: the
     * password is set again and every right beyond {@link #APPLICATION_PRIVILEGES} on the own
     * database is taken away.
     *
     * @param database database name
     * @param user user name
     * @param host host part of the user ({@code 127.0.0.1} for the managed server, {@code %} for Docker)
     * @param password the user's password
     * @return the statements
     */
    public static List<String> application(String database, String user, String host, Secret password) {
        String db = identifier(database);
        String who = account(user, host);
        String pw = literal(password.reveal());
        return List.of(
                "CREATE DATABASE IF NOT EXISTS " + db + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                "CREATE USER IF NOT EXISTS " + who + " IDENTIFIED BY " + pw,
                "ALTER USER " + who + " IDENTIFIED BY " + pw,
                "REVOKE ALL PRIVILEGES, GRANT OPTION FROM " + who,
                "GRANT " + String.join(", ", APPLICATION_PRIVILEGES) + " ON " + db + ".* TO " + who,
                "FLUSH PRIVILEGES");
    }

    /**
     * Lists the accounts of the server.
     *
     * @return the query, two columns: user and host
     */
    public static String listAccounts() {
        return "SELECT User, Host FROM mysql.user";
    }

    /**
     * Reads the connection limit of the server.
     *
     * @return the query, one row with one column
     */
    public static String maxConnections() {
        return "SELECT @@max_connections";
    }
}
