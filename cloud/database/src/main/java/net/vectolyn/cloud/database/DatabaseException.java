package net.vectolyn.cloud.database;

import java.util.Objects;

/**
 * The database could not be prepared or reached. The message says what to do; it never contains a
 * password.
 */
public final class DatabaseException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Reason {
        /** The managed mode has no MariaDB build for this operating system or CPU. */
        MANAGED_PLATFORM,
        /** Downloading or unpacking the MariaDB server failed. */
        INSTALL,
        /** Creating the data directory failed. */
        INITIALIZE,
        /** The server process did not start or stopped right away. */
        START,
        /** The server did not answer in time. */
        UNREACHABLE,
        /** The server refused the login. */
        LOGIN,
        /** A setup statement failed. */
        SETUP,
        /** More services than the connection limit of the server allows. */
        CONNECTION_BUDGET,
        /** A setting or secret is missing or invalid. */
        SETTINGS
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason what went wrong
     * @param message what happened and what to do
     */
    public DatabaseException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates the exception with a cause.
     *
     * @param reason what went wrong
     * @param message what happened and what to do
     * @param cause the cause
     */
    public DatabaseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns what went wrong.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
