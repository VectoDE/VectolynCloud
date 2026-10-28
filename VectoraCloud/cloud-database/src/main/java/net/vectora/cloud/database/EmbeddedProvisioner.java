package net.vectora.cloud.database;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;

/**
 * Mode {@code h2}: no database server. Every server keeps its data in embedded H2 files in its own
 * folder ({@code VECTORA_DB_STORAGE_METHOD=h2}); LuckPerms brings the H2 driver itself, so the cloud
 * needs no H2 library. Data is not shared between servers, so ranks set on one server do not show
 * up on another. Meant for tests, CI and a first look at the cloud on systems without a managed
 * MariaDB build.
 */
public final class EmbeddedProvisioner implements DatabaseProvisioner {

    private final DatabaseConfig config;

    /**
     * Creates the provisioner.
     *
     * @param config database settings, mode {@code h2}; name and user still fill the templates
     */
    public EmbeddedProvisioner(DatabaseConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        if (config.mode() != DatabaseMode.H2) {
            throw new IllegalArgumentException("not an embedded database: " + config.mode());
        }
    }

    @Override
    public DatabaseMode mode() {
        return DatabaseMode.H2;
    }

    @Override
    public DatabaseEndpoint provision() {
        return new DatabaseEndpoint(StorageMethod.H2, config.host(), config.port(), config.name(), config.user(),
                Optional.empty());
    }

    @Override
    public HealthReport health() {
        return new HealthReport(true, Duration.ZERO, "embedded H2, one set of files per server");
    }

    @Override
    public void shutdown() {
        // Nothing runs.
    }
}
