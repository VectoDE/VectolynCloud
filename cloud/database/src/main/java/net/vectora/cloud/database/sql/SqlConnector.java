package net.vectora.cloud.database.sql;

import java.sql.SQLException;
import net.vectora.cloud.config.secret.Secret;

/** Opens connections. {@link JdbcConnector} is the real one; tests use a fake. */
@FunctionalInterface
public interface SqlConnector {

    /**
     * Opens a connection without selecting a database.
     *
     * @param target where the server is
     * @param user the user
     * @param password the password
     * @return the open session
     * @throws SQLException if the server cannot be reached or refuses the login
     */
    SqlSession open(ConnectionTarget target, String user, Secret password) throws SQLException;
}
