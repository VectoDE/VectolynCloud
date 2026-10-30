package net.vectolyn.cloud.api.backend;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * State of this node for {@code GET /api/v1/node}.
 *
 * @param nodeId          node id from {@code cloud.yml}
 * @param version         version of VectolynCloud ({@code dev} outside the jar)
 * @param javaVersion     version of the running JVM
 * @param os              operating system and architecture
 * @param uptimeSeconds   seconds since the node started
 * @param shuttingDown    whether the node is stopping
 * @param servicesTotal   services the node knows
 * @param servicesRunning services in the state {@code running}
 * @param heapUsedBytes   heap of the node in use
 * @param heapMaxBytes    largest heap of the node
 */
@JsonPropertyOrder({"node-id", "version", "java-version", "os", "uptime-seconds", "shutting-down", "services-total",
        "services-running", "heap-used-bytes", "heap-max-bytes"})
public record NodeStatus(String nodeId, String version, String javaVersion, String os, long uptimeSeconds,
                         boolean shuttingDown, int servicesTotal, int servicesRunning, long heapUsedBytes,
                         long heapMaxBytes) {

    /** Checks the required values. */
    public NodeStatus {
        Objects.requireNonNull(nodeId, "nodeId");
    }

    /**
     * Fills Java, OS, uptime and heap from the running JVM.
     *
     * @param nodeId          node id
     * @param version         version of VectolynCloud, null for {@code dev}
     * @param startedAt       when the node started
     * @param shuttingDown    whether the node is stopping
     * @param servicesTotal   services the node knows
     * @param servicesRunning services that are running
     * @return the status
     */
    public static NodeStatus ofThisJvm(String nodeId, String version, Instant startedAt, boolean shuttingDown,
                                       int servicesTotal, int servicesRunning) {
        Runtime runtime = Runtime.getRuntime();
        long uptime = Math.max(0, Duration.between(startedAt, Instant.now()).getSeconds());
        String os = System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", "?");
        return new NodeStatus(nodeId, version == null ? "dev" : version, Runtime.version().toString(),
                os.toLowerCase(Locale.ROOT), uptime, shuttingDown, servicesTotal, servicesRunning,
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory());
    }
}
