package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One node of the cluster.
 *
 * @param id           node id, referenced by {@code cloud.yml} ({@code node-id}) and by tasks
 * @param host         address other nodes and the services reach this node at
 * @param port         port of the cloud channel (bridges, other nodes)
 * @param servicePorts ports the node hands out to the services it starts
 * @param maxMemory    memory the services of this node may use together, for example {@code 8G};
 *                     null (not written) means automatic: the measured memory minus the reserve
 * @param reserve      {@code memory-reserve} as the file has it: memory the services leave free.
 *                     null or missing fields mean their defaults; {@link #memoryReserve()} fills
 *                     them in
 */
@JsonPropertyOrder({"id", "host", "port", "service-ports", "max-memory", "memory-reserve"})
public record NodeConfig(String id, String host, int port, PortRange servicePorts, MemorySize maxMemory,
                         @JsonProperty("memory-reserve") MemoryReserve reserve) {

    /** Id of the first node. */
    public static final String DEFAULT_ID = "node-1";

    /** A node without a {@code memory-reserve} section (defaults). */
    public NodeConfig(String id, String host, int port, PortRange servicePorts, MemorySize maxMemory) {
        this(id, host, port, servicePorts, maxMemory, null);
    }

    /** A node without {@code max-memory} (automatic) and without a {@code memory-reserve} section. */
    public NodeConfig(String id, String host, int port, PortRange servicePorts) {
        this(id, host, port, servicePorts, null, null);
    }

    /** {@code node-1} on {@code 127.0.0.1:7010}, services on 30000 to 30999, memory automatic. */
    public static NodeConfig defaults() {
        return new NodeConfig(DEFAULT_ID, "127.0.0.1", 7010, new PortRange(30000, 30999));
    }

    /**
     * The reserve with every missing field at its default: system 1G, node overhead 256M,
     * database 512M.
     *
     * @return the complete reserve, never null
     */
    public MemoryReserve memoryReserve() {
        return reserve == null ? MemoryReserve.defaults() : reserve.withDefaults();
    }

    /** A copy with another {@code max-memory}; null means automatic. */
    public NodeConfig withMaxMemory(MemorySize value) {
        return new NodeConfig(id, host, port, servicePorts, value, reserve);
    }

    /**
     * A copy with another {@code memory-reserve} section. Null or a reserve without any field
     * removes the section, so every field has its default.
     */
    public NodeConfig withMemoryReserve(MemoryReserve value) {
        return new NodeConfig(id, host, port, servicePorts, maxMemory, value == null || value.noneSet() ? null : value);
    }
}
