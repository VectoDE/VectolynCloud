package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.MemorySize;

/**
 * Sets the heap of each service of a group.
 *
 * @param group group name, must exist
 * @param min   initial heap
 * @param max   maximum heap; the validator checks {@code min <= max}
 */
public record SetGroupHeap(String group, MemorySize min, MemorySize max) implements ConfigOperation {

    /** Checks the arguments. */
    public SetGroupHeap {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        return current.withGroup(group, Lookups.group(current, group).withHeap(new HeapConfig(min, max)));
    }
}
