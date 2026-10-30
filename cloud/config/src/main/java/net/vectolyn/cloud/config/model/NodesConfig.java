package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Content of {@code <home>/nodes.yml}: every node of the cluster. A single-node setup has one entry.
 *
 * @param schemaVersion version of the file format, see {@link #SCHEMA_VERSION}
 * @param nodes         the nodes, never null
 */
@JsonPropertyOrder({"schema-version", "nodes"})
public record NodesConfig(int schemaVersion, List<NodeConfig> nodes) {

    /** Current version of the {@code nodes.yml} format. */
    public static final int SCHEMA_VERSION = 1;

    /** Copies the list; a missing list becomes empty. */
    public NodesConfig {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** One node, {@link NodeConfig#defaults()}. */
    public static NodesConfig defaults() {
        return new NodesConfig(SCHEMA_VERSION, List.of(NodeConfig.defaults()));
    }

    /** The node with this id. */
    public Optional<NodeConfig> find(String id) {
        return nodes.stream().filter(n -> n.id() != null && n.id().equals(id)).findFirst();
    }

    /** A copy where {@code node} replaces the node with the same id, or is appended. */
    public NodesConfig withNode(NodeConfig node) {
        List<NodeConfig> copy = new ArrayList<>(nodes);
        int index = -1;
        for (int i = 0; i < copy.size(); i++) {
            if (node.id().equals(copy.get(i).id())) {
                index = i;
            }
        }
        if (index >= 0) {
            copy.set(index, node);
        } else {
            copy.add(node);
        }
        return new NodesConfig(schemaVersion, copy);
    }

    /** A copy without the node with this id. */
    public NodesConfig withoutNode(String id) {
        return new NodesConfig(schemaVersion, nodes.stream().filter(n -> !id.equals(n.id())).toList());
    }
}
