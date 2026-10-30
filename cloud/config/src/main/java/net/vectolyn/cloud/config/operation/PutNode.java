package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.NodeConfig;

/**
 * Adds a node to {@code nodes.yml} or replaces the node with the same id.
 *
 * @param node the node, checked by the validator
 */
public record PutNode(NodeConfig node) implements ConfigOperation {

    /** Checks the argument. */
    public PutNode {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(node.id(), "node.id");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withNodes(current.nodes().withNode(node));
    }
}
