package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.DatabaseConfig;

/**
 * Replaces all database settings (mode and its parameters).
 *
 * @param database new settings, checked by the validator
 */
public record SetDatabase(DatabaseConfig database) implements ConfigOperation {

    /** Checks the argument. */
    public SetDatabase {
        Objects.requireNonNull(database, "database");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withDatabase(database));
    }
}
