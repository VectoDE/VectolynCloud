package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Deletes task {@code name}.
 *
 * @param name task name
 */
public record RemoveTask(String name) implements ConfigOperation {

    /** Checks the argument. */
    public RemoveTask {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        Lookups.task(current, name);
        return current.withoutTask(name);
    }
}
