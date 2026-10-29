package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.TaskConfig;

/**
 * Creates task {@code name} or replaces it completely.
 *
 * @param name task name, also the file name; checked by the validator
 * @param task new content; its group must exist
 */
public record PutTask(String name, TaskConfig task) implements ConfigOperation {

    /** Checks the arguments. */
    public PutTask {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withTask(name, task);
    }
}
