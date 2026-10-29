package net.vectora.cloud.download;

import java.io.IOException;

/**
 * Base type of every failure in this module that is not a plain I/O error.
 *
 * <p>The subclasses say what went wrong, so callers can tell a server that is down (worth a retry
 * later) from a download that must never be used (wrong checksum, foreign host).
 */
public class DownloadException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong, in words an administrator understands
     */
    public DownloadException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what went wrong
     * @param cause the underlying error
     */
    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
