// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * {@link PostgresConnector} on the PostgreSQL JDBC driver (pgjdbc). User and password travel in the
 * connection properties, never in the URL.
 */
public final class PostgresJdbcConnector implements PostgresConnector {

    private final org.postgresql.Driver driver = new org.postgresql.Driver();

    /** Creates the connector. */
    public PostgresJdbcConnector() {
    }

    @Override
    public SqlSession open(ConnectionTarget target, String database, String user, Secret password) throws SQLException {
        String url = url(target, database);
        Connection connection = driver.connect(url, properties(target, user, password));
        if (connection == null) {
            throw new SQLException("the PostgreSQL driver does not accept " + url);
        }
        return new JdbcSession(connection);
    }

    /**
     * The JDBC URL with host, port and database; no user, no password.
     *
     * @param target   the server
     * @param database the database
     * @return {@code jdbc:postgresql://host:port/database}
     */
    static String url(ConnectionTarget target, String database) {
        String host = target.host();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "jdbc:postgresql://" + host + ":" + target.port() + "/" + URLEncoder.encode(database, StandardCharsets.UTF_8);
    }

    /**
     * The connection properties.
     *
     * @param target   the server
     * @param user     the user
     * @param password the password
     * @return the properties
     */
    static Properties properties(ConnectionTarget target, String user, Secret password) {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password.reveal());
        // disable for loopback only, verify-full (certificate and host name) for every other host.
        p.setProperty("sslmode", target.tls().sslMode());
        if (target.tls() == TlsMode.VERIFY_FULL) {
            // The JVM trust store, like MariaDB Connector/J; pgjdbc would otherwise want ~/.postgresql/root.crt.
            // pgjdbc still checks the host name for verify-full.
            p.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        }
        String seconds = Long.toString(Math.max(1, target.timeout().toSeconds()));
        p.setProperty("connectTimeout", seconds);
        p.setProperty("socketTimeout", seconds);
        p.setProperty("loginTimeout", seconds);
        p.setProperty("ApplicationName", "VectolynCloud");
        // Plain statements only, so a value can never turn into a second statement of a prepared batch.
        p.setProperty("preferQueryMode", "simple");
        return p;
    }
}
