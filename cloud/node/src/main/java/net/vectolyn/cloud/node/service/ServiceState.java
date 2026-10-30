package net.vectolyn.cloud.node.service;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Life cycle of a service.
 *
 * <pre>
 * PREPARED ──▶ STARTING ──▶ RUNNING ──▶ STOPPING ──▶ STOPPED
 *                 │            │
 *                 └──────┬─────┘
 *                        └──── exit without a stop request ──▶ CRASHED
 * STOPPED, CRASHED ──▶ PREPARED  (next start of a static service, restart after a crash)
 * CRASHED ──▶ STOPPED            (stopped by the operator while a restart was pending)
 * </pre>
 *
 * <p>A process that ends while the node asked it to stop is {@link #STOPPED}, whatever its exit
 * code. A process that ends without being asked is {@link #CRASHED}, also with exit code 0
 * (CraftBukkit 1.8 on an unknown option, Spigot's watchdog or {@code /restart} without a restart
 * script, {@code /stop} typed in game), so the restart after a crash brings it back. Only while the
 * node itself shuts down is an exit with 0 {@link #STOPPED}.
 */
public enum ServiceState {
    /** Id, port and folder are assigned; the process does not run yet. */
    @JsonProperty("prepared") PREPARED,
    /** The process runs, the server is still loading. */
    @JsonProperty("starting") STARTING,
    /** The server reported that it is ready ({@code Done (…)!} or the bridge). */
    @JsonProperty("running") RUNNING,
    /** The stop command was sent; the node waits for the process to end. */
    @JsonProperty("stopping") STOPPING,
    /** The process ended normally or was stopped by the node. */
    @JsonProperty("stopped") STOPPED,
    /** The process ended without a stop request (whatever its exit code), or could not be started. */
    @JsonProperty("crashed") CRASHED;

    /**
     * Tells whether a process may be running in this state.
     *
     * @return true for {@link #STARTING}, {@link #RUNNING} and {@link #STOPPING}
     */
    public boolean hasProcess() {
        return this == STARTING || this == RUNNING || this == STOPPING;
    }

    /**
     * Tells whether the life cycle allows the step from this state to {@code next}.
     *
     * @param next the new state
     * @return whether the transition is allowed
     */
    public boolean canBecome(ServiceState next) {
        return switch (this) {
            case PREPARED -> next == STARTING || next == STOPPED || next == CRASHED;
            case STARTING -> next == RUNNING || next == STOPPING || next == STOPPED || next == CRASHED;
            case RUNNING -> next == STOPPING || next == STOPPED || next == CRASHED;
            case STOPPING -> next == STOPPED;
            case STOPPED -> next == PREPARED;
            case CRASHED -> next == PREPARED || next == STOPPED;
        };
    }
}
