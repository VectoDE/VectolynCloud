package net.vectolyn.cloud.launcher.security;

import java.util.Objects;

/**
 * Result of the root/administrator check.
 *
 * @param state    whether the process has elevated rights
 * @param evidence what the decision is based on, for log and error messages
 *                 (for example {@code "UnixSystem uid=0"} or {@code "S-1-16-12288"})
 */
public record Elevation(State state, String evidence) {

    /** Outcome of the check. */
    public enum State {
        /** Runs as root (UID 0) or as an elevated Windows process. */
        ELEVATED,
        /** Runs as a normal user. */
        NOT_ELEVATED,
        /** No detection method worked. The launcher starts, but says so. */
        UNKNOWN
    }

    /** Validates that no component is {@code null}. */
    public Elevation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * Tells whether the process is known to be elevated.
     *
     * @return {@code true} only for {@link State#ELEVATED}
     */
    public boolean elevated() {
        return state == State.ELEVATED;
    }
}
