// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** {@link SqlSession} on a JDBC connection; plain statements only, one at a time. */
final class JdbcSession implements SqlSession {

    private final Connection connection;

    JdbcSession(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public List<List<String>> query(String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData meta = result.getMetaData();
            int columns = meta.getColumnCount();
            while (result.next()) {
                List<String> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    String value = result.getString(i);
                    row.add(value == null ? "" : value);
                }
                rows.add(List.copyOf(row));
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public String serverVersion() throws SQLException {
        return connection.getMetaData().getDatabaseProductVersion();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
