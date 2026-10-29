package net.vectora.cloud.api;

import java.io.IOException;
import java.io.Serial;

/** The API cannot start with these settings, for example a public address without TLS. */
public final class ApiStartException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong and how to fix it, without secrets
     */
    public ApiStartException(String message) {
        super(message);
    }
}
