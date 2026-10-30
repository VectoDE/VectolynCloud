package net.vectolyn.cloud.backup;

import java.util.Objects;

/**
 * A backup operation failed or was refused. The message never contains a password; it may name
 * files, services and backup ids.
 */
public final class BackupException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why it failed, for the console and later the API. */
    public enum Reason {
        /** {@code backup.yml} is broken or invalid. */
        CONFIG,
        /** Another backup, restore or delete is running. */
        BUSY,
        /** No backup with this id. */
        NOT_FOUND,
        /** No static service with this id. */
        UNKNOWN_SERVICE,
        /** The service must be stopped for this (restore). */
        SERVICE_RUNNING,
        /** The service is starting or stopping; its files are in flux. */
        SERVICE_BUSY,
        /** {@code save-off}/{@code save-all flush} did not confirm in time. */
        SAVE_FAILED,
        /** The archive or its manifest is broken or was changed. */
        CORRUPT,
        /** A file's hash differs from the manifest. */
        HASH_MISMATCH,
        /** The database dump failed. */
        DATABASE,
        /** Reading or writing files failed. */
        IO
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why
     * @param message what happened, without secrets
     */
    public BackupException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates the exception with a cause.
     *
     * @param reason why
     * @param message what happened, without secrets
     * @param cause the cause
     */
    public BackupException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Why it failed. */
    public Reason reason() {
        return reason;
    }
}
