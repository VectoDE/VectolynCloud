package net.vectolyn.cloud.node.state;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Content of {@code state/services.json}.
 *
 * @param schemaVersion version of the format, see {@link #SCHEMA_VERSION}
 * @param node          id of the node that wrote the file
 * @param services      every service the node knew, never null
 */
@JsonPropertyOrder({"schema-version", "node", "services"})
public record ServiceStates(int schemaVersion, String node, List<ServiceRecord> services) {

    /** Current version of the format. */
    public static final int SCHEMA_VERSION = 1;

    /** Copies the list; a missing list becomes empty. */
    public ServiceStates {
        services = services == null ? List.of() : List.copyOf(services);
    }

    /**
     * An empty state for a node.
     *
     * @param node node id
     * @return the state without services
     */
    public static ServiceStates empty(String node) {
        return new ServiceStates(SCHEMA_VERSION, node, List.of());
    }
}
