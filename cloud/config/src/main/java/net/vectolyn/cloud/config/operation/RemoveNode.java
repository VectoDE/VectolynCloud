package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.validation.ConfigNames;

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
            throw new ConfigException(ConfigError.of(CloudHome.NODES_FILE, "nodes", ErrorCode.UNKNOWN_NODE, ConfigNames.safeArgument(id)));
        }
        return current.withNodes(current.nodes().withoutNode(id));
    }
}
