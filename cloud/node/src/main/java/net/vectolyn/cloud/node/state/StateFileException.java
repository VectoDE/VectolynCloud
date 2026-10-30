package net.vectolyn.cloud.node.state;

import java.io.Serial;

/** {@code state/services.json} is not valid. The message names the problem, never file content. */
public final class StateFileException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong
     */
    public StateFileException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what is wrong
     * @param cause   the parser failure
     */
    public StateFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
