package net.vectora.cloud.node.service;

import java.io.Serial;

/**
 * A service could not be planned, prepared, started or controlled. The message never contains a
 * secret or a service token.
 */
public final class ServiceException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public ServiceException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
