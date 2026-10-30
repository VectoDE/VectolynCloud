package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;

/**
 * Creates group {@code name} or replaces it completely.
 *
 * @param name  group name, also the file name; checked by the validator
 * @param group new content
 */
public record PutGroup(String name, GroupConfig group) implements ConfigOperation {

    /** Checks the arguments. */
    public PutGroup {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(group, "group");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withGroup(name, group);
    }
}
