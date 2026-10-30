package net.vectolyn.cloud.download.archive;

import java.io.IOException;

/**
 * An archive is malformed or breaks a safety rule: path outside the target, link outside the
 * target, unsupported entry type, too many entries, too many bytes.
 */
public final class ArchiveException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong
     */
    public ArchiveException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what is wrong
     * @param cause the underlying error
     */
    public ArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
