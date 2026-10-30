package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;

/**
 * Switches the database mode and keeps the other database settings. Switching to {@code docker}
 * sets {@link DatabaseConfig#DEFAULT_DOCKER_IMAGE} if no image is set yet. Use {@link SetDatabase} to
 * change host, port and names in the same step.
 *
 * @param mode new mode
 */
public record SetDatabaseMode(DatabaseMode mode) implements ConfigOperation {

    /** Checks the argument. */
    public SetDatabaseMode {
        Objects.requireNonNull(mode, "mode");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        DatabaseConfig db = current.cloud().database() == null ? DatabaseConfig.defaults() : current.cloud().database();
        return current.withCloud(current.cloud().withDatabase(db.withMode(mode)));
    }
}
