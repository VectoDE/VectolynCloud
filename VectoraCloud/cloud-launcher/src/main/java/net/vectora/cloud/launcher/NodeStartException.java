package net.vectora.cloud.launcher;

import java.io.Serial;

/** The node could not be found or failed while starting. */
public final class NodeStartException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** The entry class does not exist in this build. */
        MISSING,
        /** The entry class exists but has no {@code public static void start(Map<String, String>)}. */
        INVALID_ENTRY,
        /** The entry method threw. */
        FAILED
    }

    private final Kind kind;

    /**
     * Creates the exception.
     *
     * @param kind    what went wrong
     * @param message technical detail
     * @param cause   underlying exception, may be {@code null}
     */
    public NodeStartException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * Returns what went wrong.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }
}
