package net.vectora.protocol.message;

import java.util.Objects;

/**
 * A decoded message together with the request id of its frame.
 *
 * @param requestId pairs a {@link Request} with its {@link Response}; {@link #NO_REQUEST} for
 *     every other message
 * @param message the message
 */
public record Envelope(int requestId, Message message) {

    /** Request id of messages that are not part of a request/response pair. */
    public static final int NO_REQUEST = 0;

    /**
     * Creates an envelope.
     *
     * @throws IllegalArgumentException if the request id is negative, missing for a request or
     *     response, or set for any other message
     */
    public Envelope {
        Objects.requireNonNull(message, "message");
        if (requestId < 0) {
            throw new IllegalArgumentException("request id must not be negative: " + requestId);
        }
        String type = message.getClass().getSimpleName();
        if (message.requiresRequestId() && requestId == NO_REQUEST) {
            throw new IllegalArgumentException(type + " needs a request id");
        }
        if (!message.requiresRequestId() && requestId != NO_REQUEST) {
            throw new IllegalArgumentException(type + " must not carry a request id");
        }
    }

    /** Wraps a message that is not a request or response. */
    public static Envelope of(Message message) {
        return new Envelope(NO_REQUEST, message);
    }
}
