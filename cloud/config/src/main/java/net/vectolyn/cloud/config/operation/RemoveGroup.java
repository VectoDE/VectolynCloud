package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Deletes group {@code name}. The validator rejects this while a task still uses the group.
 *
 * @param name group name
 */
public record RemoveGroup(String name) implements ConfigOperation {

    /** Checks the argument. */
    public RemoveGroup {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        Lookups.group(current, name);
        return current.withoutGroup(name);
    }
}
