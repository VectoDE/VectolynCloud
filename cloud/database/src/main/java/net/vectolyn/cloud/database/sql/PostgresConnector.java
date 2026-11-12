// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import java.sql.SQLException;
import net.vectolyn.cloud.config.secret.Secret;

/** Opens connections to a PostgreSQL server. {@link PostgresJdbcConnector} is the real one; tests use a fake. */
@FunctionalInterface
public interface PostgresConnector {

    /**
     * Opens a connection to one database. PostgreSQL has no {@code USE}: the database is part of the
     * login.
     *
     * @param target   where the server is
     * @param database the database
     * @param user     the user
     * @param password the password
     * @return the open session
     * @throws SQLException if the server cannot be reached or refuses the login
     */
    SqlSession open(ConnectionTarget target, String database, String user, Secret password) throws SQLException;
}
