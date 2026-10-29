package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.TimeSpan;

/**
 * Sets the {@code drain-timeout} of a group.
 *
 * @param group group name, must exist
 * @param value the new value; null removes the field, so the default applies. The validator checks
 *              the range.
 */
public record SetGroupDrainTimeout(String group, TimeSpan value) implements ConfigOperation {

    /** Checks the group name. */
    public SetGroupDrainTimeout {
        Objects.requireNonNull(group, "group");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        return current.withGroup(group, Lookups.group(current, group).withDrainTimeout(value));
    }
}
