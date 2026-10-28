package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.ScalingConfig;

/**
 * Sets service count and scaling of a task.
 *
 * @param task    task name, must exist
 * @param scaling new scaling, checked by the validator
 */
public record SetTaskScaling(String task, ScalingConfig scaling) implements ConfigOperation {

    /** Checks the arguments. */
    public SetTaskScaling {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(scaling, "scaling");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        return current.withTask(task, Lookups.task(current, task).withScaling(scaling));
    }
}
