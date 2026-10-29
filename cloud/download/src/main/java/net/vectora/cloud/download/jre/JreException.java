package net.vectora.cloud.download.jre;

import net.vectora.cloud.download.DownloadException;

/**
 * A Java runtime cannot be chosen or installed: the configured version is too old for the server,
 * or an unpacked archive is not the runtime it should be.
 */
public final class JreException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong, in words an administrator understands
     */
    public JreException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what went wrong
     * @param cause the underlying error
     */
    public JreException(String message, Throwable cause) {
        super(message, cause);
    }
}
