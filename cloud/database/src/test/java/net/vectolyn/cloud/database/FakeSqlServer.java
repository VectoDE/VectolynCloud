package net.vectolyn.cloud.database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.database.sql.ConnectionTarget;
import net.vectolyn.cloud.database.sql.SqlConnector;
import net.vectolyn.cloud.database.sql.SqlSession;

/** A pretend database server for unit tests: accepts known logins and records every statement. */
public final class FakeSqlServer implements SqlConnector {

    private static final java.util.regex.Pattern PASSWORD_CHANGE =
            java.util.regex.Pattern.compile("ALTER USER '([^']+)'@'[^']*' IDENTIFIED BY '([^']*)'");

    /** Whether the server accepts connections. */
    public volatile boolean up = true;
    /** Value of {@code @@max_connections}, or null if the query fails. */
    public String maxConnections = "151";
    /** Version string it reports. */
    public String version = "11.8.9-MariaDB";
    /** Accepted logins. */
    public final Map<String, String> passwords = new HashMap<>();
    /** Rows of {@code SELECT User, Host FROM mysql.user}. */
    public final List<List<String>> accounts = new ArrayList<>();
    /** Every statement, prefixed with the user. */
    public final List<String> log = new ArrayList<>();
    /** Statements that fail. */
    public final List<String> failing = new ArrayList<>();
    /** Targets that were opened. */
    public final List<ConnectionTarget> targets = new ArrayList<>();
    /** How many open attempts there were. */
    public int attempts;

    @Override
    public synchronized SqlSession open(ConnectionTarget target, String user, Secret password) throws SQLException {
        attempts++;
        targets.add(target);
        if (!up) {
            throw new SQLException("Connection refused", "08000", 0);
        }
        String expected = passwords.get(user);
        if (expected == null || !expected.equals(password.reveal())) {
            throw new SQLException("Access denied for user '" + user + "'", "28000", 1045);
        }
        return new SqlSession() {
            @Override
            public void execute(String sql) throws SQLException {
                synchronized (FakeSqlServer.this) {
                    log.add(user + ": " + sql);
                    for (String f : failing) {
                        if (sql.startsWith(f)) {
                            throw new SQLException("refused, password " + password.reveal() + " seen", "HY000", 1);
                        }
                    }
                    if (sql.equals("SHUTDOWN")) {
                        up = false;
                    }
                    java.util.regex.Matcher m = PASSWORD_CHANGE.matcher(sql);
                    if (m.matches()) {
                        passwords.put(m.group(1), m.group(2));
                    }
                }
            }

            @Override
            public List<List<String>> query(String sql) throws SQLException {
                synchronized (FakeSqlServer.this) {
                    log.add(user + ": " + sql);
                    if (sql.startsWith("SELECT User, Host")) {
                        return List.copyOf(accounts);
                    }
                    if (sql.equals("SELECT @@max_connections")) {
                        if (maxConnections == null) {
                            throw new SQLException("no such variable", "HY000", 1193);
                        }
                        return List.of(List.of(maxConnections));
                    }
                    return List.of(List.of("1"));
                }
            }

            @Override
            public String serverVersion() {
                return version;
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * Returns the statements a user ran, without the prefix.
     *
     * @param user the user
     * @return the statements
     */
    public synchronized List<String> statementsOf(String user) {
        String prefix = user + ": ";
        return log.stream().filter(s -> s.startsWith(prefix)).map(s -> s.substring(prefix.length())).toList();
    }
}
