package net.vectora.cloud.bridge.forwarding;

import java.io.Serial;

/**
 * A forwarded handshake or its properties JSON does not have the expected form. The message
 * describes the problem and never contains the handshake or a token.
 */
public final class MalformedHandshakeException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public MalformedHandshakeException(String message) {
        super(message);
    }
}
