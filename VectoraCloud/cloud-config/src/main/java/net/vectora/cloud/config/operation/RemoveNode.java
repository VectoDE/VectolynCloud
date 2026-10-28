package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;

/**
 * Removes a node from {@code nodes.yml}. The validator rejects this while {@code cloud.yml} or a
 * task still refers to it.
 *
 * @param id node id
 */
public record RemoveNode(String id) implements ConfigOperation {

    /** Checks the argument. */
    public RemoveNode {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) throws ConfigException {
        if (current.nodes().find(id).isEmpty()) {
            throw new ConfigException(ConfigError.of(CloudHome.NODES_FILE, "nodes", ErrorCode.UNKNOWN_NODE, id));
        }
        return current.withNodes(current.nodes().withoutNode(id));
    }
}
