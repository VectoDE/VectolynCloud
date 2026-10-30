package net.vectolyn.cloud.api.backend;

import java.io.Serial;
import java.util.Objects;

/**
 * A request the backend refuses. The {@link Reason} picks the HTTP status, the message is sent to
 * the client as it is (after the redactor of {@link ApiBackend#redact}), so it must never contain
 * a secret or a path outside the cloud home.
 */
public final class ApiBackendException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the backend refused. */
    public enum Reason {
        /** The service, task or backup does not exist (404). */
        NOT_FOUND,
        /** The current state does not allow it, for example a command to a stopped service (409). */
        CONFLICT,
        /** The node cannot do it right now, for example during shutdown or before the setup (503). */
        UNAVAILABLE,
        /** The request is understood but not valid for the backend (422). */
        INVALID
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  why
     * @param message text for the client, in English, without secrets
     */
    public ApiBackendException(Reason reason, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Why the backend refused.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
