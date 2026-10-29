package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Content of {@code <home>/tasks/<name>.yml}: which services of a group run, how many, which of
 * them are static, and where. The task name is the file name.
 *
 * <p>Version 1 had a field {@code mode: static|dynamic} for the whole task. Since version 2 the
 * mode is decided per service by {@link ScalingConfig#staticServices()}; a version 1 file is
 * migrated on reading (see {@code docs/cloud/config.md}).
 *
 * @param schemaVersion version of the file format, see {@link #SCHEMA_VERSION}
 * @param group         group the services run, must exist in {@code groups/}
 * @param scaling       service counts and scaling
 * @param nodes         node ids the services may run on; empty means any node. Never null.
 */
@JsonPropertyOrder({"schema-version", "group", "scaling", "nodes"})
public record TaskConfig(int schemaVersion, String group, ScalingConfig scaling, List<String> nodes) {

    /** Current version of the task file format. */
    public static final int SCHEMA_VERSION = 2;

    /** Copies the node list; a missing list becomes empty. */
    public TaskConfig {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** A copy with other scaling. */
    public TaskConfig withScaling(ScalingConfig value) {
        return new TaskConfig(schemaVersion, group, value, nodes);
    }

    /** A copy with another group. */
    public TaskConfig withGroup(String value) {
        return new TaskConfig(schemaVersion, value, scaling, nodes);
    }

    /** A copy with other nodes; empty means any node. */
    public TaskConfig withNodes(List<String> value) {
        return new TaskConfig(schemaVersion, group, scaling, value);
    }
}
