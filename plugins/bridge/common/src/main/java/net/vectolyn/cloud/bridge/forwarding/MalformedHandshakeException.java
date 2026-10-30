package net.vectolyn.cloud.bridge.forwarding;

import java.io.Serial;

/**
 * A forwarded handshake or its properties JSON does not have the expected form. The message
 * describes the problem and never contains the handshake or a token.
 */
public final class MalformedHandshakeException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception without a stack trace: it is thrown for every forged or broken
     * handshake, and a flood of those should cost as little as possible.
     */
    public MalformedHandshakeException(String message) {
        super(message, null, false, false);
    }
}
