package net.vectolyn.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * One unit on the wire, before its payload is turned into a message. Layout and limits are
 * described in {@link FrameCodec}.
 *
 * <p>The payload array is copied in and out, so a frame is immutable. {@link #toString()} shows
 * only the payload length, because a payload may hold a token.
 *
 * @param version protocol version of the sender
 * @param messageId id of the message type, 0 or more
 * @param requestId id that pairs a request with its response, 0 for none
 * @param payload encoded message
 */
public record Frame(ProtocolVersion version, int messageId, int requestId, byte[] payload) {

    /**
     * Creates a frame.
     *
     * @throws IllegalArgumentException if an id is negative or the frame body would exceed
     *     {@link Protocol#MAX_FRAME_SIZE}
     */
    public Frame {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(payload, "payload");
        if (messageId < 0) {
            throw new IllegalArgumentException("message id must not be negative: " + messageId);
        }
        if (requestId < 0) {
            throw new IllegalArgumentException("request id must not be negative: " + requestId);
        }
        long bodySize = headerSize(messageId, requestId) + (long) payload.length;
        if (bodySize > Protocol.MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("frame body of " + bodySize + " bytes exceeds the limit of "
                    + Protocol.MAX_FRAME_SIZE);
        }
        payload = payload.clone();
    }

    /** Returns a copy of the payload. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Returns the size of the frame body: header plus payload, without the length prefix. */
    public int bodySize() {
        return headerSize(messageId, requestId) + payload.length;
    }

    static int headerSize(int messageId, int requestId) {
        return 2 + VarInts.size(messageId) + VarInts.size(requestId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Frame frame
                && version.equals(frame.version)
                && messageId == frame.messageId
                && requestId == frame.requestId
                && Arrays.equals(payload, frame.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, messageId, requestId, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "Frame[version=" + version + ", messageId=" + messageId + ", requestId=" + requestId
                + ", payload=" + payload.length + " bytes]";
    }
}
