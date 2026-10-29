package net.vectora.cloud.database.sql;

import java.sql.SQLException;
import java.util.List;

/** An open connection to a database server, reduced to what the setup needs. */
public interface SqlSession extends AutoCloseable {

    /**
     * Runs a statement that returns no rows.
     *
     * @param sql the statement
     * @throws SQLException if the server refuses it
     */
    void execute(String sql) throws SQLException;

    /**
     * Runs a query and returns every row as text.
     *
     * @param sql the query
     * @return the rows; {@code null} columns become empty strings
     * @throws SQLException if the server refuses it
     */
    List<List<String>> query(String sql) throws SQLException;

    /**
     * Returns the version string of the server, for example {@code 11.8.9-MariaDB}.
     *
     * @return the version string
     * @throws SQLException if it cannot be read
     */
    String serverVersion() throws SQLException;

    @Override
    void close() throws SQLException;
}
