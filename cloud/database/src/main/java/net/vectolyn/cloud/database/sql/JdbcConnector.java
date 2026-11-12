// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * {@link SqlConnector} on MariaDB Connector/J. User and password travel in the connection
 * properties, never in the URL.
 */
public final class JdbcConnector implements SqlConnector {

    private final org.mariadb.jdbc.Driver driver = new org.mariadb.jdbc.Driver();

    /** Creates the connector. */
    public JdbcConnector() {
    }

    @Override
    public SqlSession open(ConnectionTarget target, String user, Secret password) throws SQLException {
        Connection connection = driver.connect(target.jdbcUrl(), properties(target, user, password));
        if (connection == null) {
            throw new SQLException("MariaDB Connector/J does not accept " + target.jdbcUrl());
        }
        return new JdbcSession(connection);
    }

    /**
     * Builds the connection properties.
     *
     * @param target the target
     * @param user the user
     * @param password the password
     * @return the properties
     */
    static Properties properties(ConnectionTarget target, String user, Secret password) {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password.reveal());
        p.setProperty("sslMode", target.tls().sslMode());
        String millis = Long.toString(target.timeout().toMillis());
        p.setProperty("connectTimeout", millis);
        p.setProperty("socketTimeout", millis);
        // The setup sends one statement at a time; a value can never turn into a second statement.
        p.setProperty("allowMultiQueries", "false");
        p.setProperty("allowLocalInfile", "false");
        return p;
    }
}
