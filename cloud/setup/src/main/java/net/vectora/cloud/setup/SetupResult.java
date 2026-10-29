package net.vectora.cloud.setup;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * How a run of the assistant ended.
 *
 * @param status what happened
 * @param files  files written, relative to the home, sorted; empty unless something was saved
 */
public record SetupResult(Status status, Set<String> files) {

    /** How the run ended. */
    public enum Status {
        /** {@code setup-complete} was already set; the assistant did nothing. */
        ALREADY_COMPLETE,
        /** The answers were saved and {@code setup-complete} is set now. */
        COMPLETED,
        /** Running again: the answers were saved. */
        CHANGED,
        /** Running again: the answers match the configuration, nothing was written. */
        UNCHANGED,
        /** The operator stopped, or the input ended; nothing was written. */
        CANCELLED,
        /** The operator declined the Minecraft EULA; nothing was written. */
        EULA_DECLINED
    }

    /** Copies the files. */
    public SetupResult {
        Objects.requireNonNull(status, "status");
        files = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(files, "files")));
    }

    /** A result without files. */
    public static SetupResult of(Status status) {
        return new SetupResult(status, Set.of());
    }

    /** Whether the node may go on and start servers: the setup is complete now or was before. */
    public boolean ready() {
        return status == Status.ALREADY_COMPLETE || status == Status.COMPLETED || status == Status.CHANGED
                || status == Status.UNCHANGED;
    }
}
