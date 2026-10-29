package net.vectora.cloud.download.lock;

import java.io.IOException;

/** {@code software.lock.yml} cannot be read: broken YAML, unknown keys, wrong types or values. */
public final class LockFileException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong and where
     */
    public LockFileException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what is wrong
     * @param cause the parser error
     */
    public LockFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
