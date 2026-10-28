package net.vectora.cloud.database;

import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.database.sql.ConnectionTarget;
import net.vectora.cloud.database.sql.ServerVersion;
import net.vectora.cloud.database.sql.SqlConnector;

/**
 * Mode {@code external}: a MariaDB or MySQL server the operator runs. The cloud creates nothing
 * there; it checks that the application user can log in and use its database. Database and user
 * must exist with the rights listed in docs/cloud/database.md.
 */
public final class ExternalProvisioner implements DatabaseProvisioner {

    private final DatabaseConfig config;
    private final Secret password;
    private final SqlConnector connector;
    private final ConnectionTarget target;

    /**
     * Creates the provisioner.
     *
     * @param config database settings, mode {@code external}
     * @param password the application password ({@code VECTORA_DB_PASSWORD})
     * @param connector the connector
     */
    public ExternalProvisioner(DatabaseConfig config, Secret password, SqlConnector connector) {
        this.config = Objects.requireNonNull(config, "config");
        if (config.mode() != DatabaseMode.EXTERNAL) {
            throw new IllegalArgumentException("not an external database: " + config.mode());
        }
        this.password = Objects.requireNonNull(password, "password");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.target = ConnectionTarget.of(config.host(), config.port());
    }

    @Override
    public DatabaseMode mode() {
        return DatabaseMode.EXTERNAL;
    }

    @Override
    public DatabaseEndpoint provision() throws DatabaseException {
        if (password.isEmpty()) {
            throw new DatabaseException(DatabaseException.Reason.SETTINGS, "VECTORA_DB_PASSWORD in"
                    + " secrets/vectora.env is empty. Enter the password of user '" + config.user()
                    + "' of your database server there.");
        }
        Optional<ServerVersion> version = ServerSetup.checkApplicationLogin(connector, target, config, password);
        StorageMethod method = version.map(v -> v.mariaDb() ? StorageMethod.MARIADB : StorageMethod.MYSQL)
                .orElse(StorageMethod.MARIADB);
        return new DatabaseEndpoint(method, config.host(), config.port(), config.name(), config.user(), version);
    }

    @Override
    public HealthReport health() {
        return ServerSetup.health(connector, target, config.user(), password);
    }

    @Override
    public void shutdown() {
        // The operator runs the server.
    }
}
