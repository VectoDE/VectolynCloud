package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;

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
