package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;

/**
 * Sets the group's own "wanted" switch, for example to switch off one minigame out of several. The
 * switch of the server kind in {@code cloud.yml} still applies on top: a group runs only if both
 * are on. {@code true} removes the entry from the group file, because wanted is the default.
 *
 * @param group  group name, must exist
 * @param wanted whether the group may run
 */
public record SetGroupWanted(String group, boolean wanted) implements ConfigOperation {

    /** Checks the arguments. */
    public SetGroupWanted {
        Objects.requireNonNull(group, "group");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        return current.withGroup(group, Lookups.group(current, group).withWanted(wanted));
    }
}
