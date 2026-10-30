package net.vectolyn.cloud.monitor;

import java.util.Locale;

/**
 * Life cycle state of a service as the monitor sees it. Same names as {@code ServiceState} in
 * cloud-node, so the node maps one to the other with {@code ServicePhase.valueOf(state.name())}.
 */
public enum ServicePhase {
    /** Id, port and folder assigned; no process yet. */
    PREPARED,
    /** The process runs, the server is still loading. */
    STARTING,
    /** The server is ready. Gauge rules (TPS, heap, ...) are evaluated only in this phase. */
    RUNNING,
    /** The stop command was sent. */
    STOPPING,
    /** The process ended normally or was stopped. */
    STOPPED,
    /** The process ended with an error or could not be started. */
    CRASHED;

    /** Lower-case name for the console. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
