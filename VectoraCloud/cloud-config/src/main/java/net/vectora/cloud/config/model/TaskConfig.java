package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Content of {@code <home>/tasks/<name>.yml}: which services of a group run, how many, and where.
 * The task name is the file name.
 *
 * @param schemaVersion version of the file format, see {@link #SCHEMA_VERSION}
 * @param group         group the services run, must exist in {@code groups/}
 * @param mode          static or dynamic
 * @param scaling       service count and scaling
 * @param nodes         node ids the services may run on; empty means any node. Never null.
 */
@JsonPropertyOrder({"schema-version", "group", "mode", "scaling", "nodes"})
public record TaskConfig(int schemaVersion, String group, TaskMode mode, ScalingConfig scaling, List<String> nodes) {

    /** Current version of the task file format. */
    public static final int SCHEMA_VERSION = 1;

    /** Copies the node list; a missing list becomes empty. */
    public TaskConfig {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** A copy with other scaling. */
    public TaskConfig withScaling(ScalingConfig value) {
        return new TaskConfig(schemaVersion, group, mode, value, nodes);
    }
}
