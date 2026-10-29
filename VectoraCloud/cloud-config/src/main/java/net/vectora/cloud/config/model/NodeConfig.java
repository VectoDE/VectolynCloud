package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One node of the cluster.
 *
 * @param id           node id, referenced by {@code cloud.yml} ({@code node-id}) and by tasks
 * @param host         address other nodes and the services reach this node at
 * @param port         port of the cloud channel (bridges, other nodes)
 * @param servicePorts ports the node hands out to the services it starts
 * @param maxMemory    memory the services of this node may use together, for example {@code 8G};
 *                     null (not written) means automatic: the measured memory minus a reserve
 */
@JsonPropertyOrder({"id", "host", "port", "service-ports", "max-memory"})
public record NodeConfig(String id, String host, int port, PortRange servicePorts, MemorySize maxMemory) {

    /** Id of the first node. */
    public static final String DEFAULT_ID = "node-1";

    /** A node without {@code max-memory} (automatic). */
    public NodeConfig(String id, String host, int port, PortRange servicePorts) {
        this(id, host, port, servicePorts, null);
    }

    /** {@code node-1} on {@code 127.0.0.1:7010}, services on 30000 to 30999, memory automatic. */
    public static NodeConfig defaults() {
        return new NodeConfig(DEFAULT_ID, "127.0.0.1", 7010, new PortRange(30000, 30999));
    }

    /** A copy with another {@code max-memory}; null means automatic. */
    public NodeConfig withMaxMemory(MemorySize value) {
        return new NodeConfig(id, host, port, servicePorts, value);
    }
}
