package net.vectora.cloud.config.model;

/**
 * One node of the cluster.
 *
 * @param id           node id, referenced by {@code cloud.yml} ({@code node-id}) and by tasks
 * @param host         address other nodes and the services reach this node at
 * @param port         port of the cloud channel (bridges, other nodes)
 * @param servicePorts ports the node hands out to the services it starts
 */
public record NodeConfig(String id, String host, int port, PortRange servicePorts) {

    /** Id of the first node. */
    public static final String DEFAULT_ID = "node-1";

    /** {@code node-1} on {@code 127.0.0.1:7010}, services on 30000 to 30999. */
    public static NodeConfig defaults() {
        return new NodeConfig(DEFAULT_ID, "127.0.0.1", 7010, new PortRange(30000, 30999));
    }
}
