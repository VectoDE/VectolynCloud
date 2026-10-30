package net.vectolyn.protocol;

import java.util.Objects;

/**
 * The fixed part at the start of every frame body: version, message id and request id. The
 * payload follows directly after it.
 *
 * @param version protocol version of the sender
 * @param messageId id of the message type, 0 or more
 * @param requestId id that pairs a request with its response, 0 for none
 */
public record FrameHeader(ProtocolVersion version, int messageId, int requestId) {

    /**
     * Creates a header.
     *
     * @throws IllegalArgumentException if an id is negative
     */
    public FrameHeader {
        Objects.requireNonNull(version, "version");
        if (messageId < 0) {
            throw new IllegalArgumentException("message id must not be negative: " + messageId);
        }
        if (requestId < 0) {
            throw new IllegalArgumentException("request id must not be negative: " + requestId);
        }
    }

    /**
     * Reads the header from the start of a frame body. Afterwards {@code in} stands at the first
     * payload byte.
     *
     * @throws ProtocolException if the header is cut off or an id is negative
     */
    public static FrameHeader read(PacketReader in) {
        int major = in.readUnsignedByte();
        int minor = in.readUnsignedByte();
        int messageId = in.readVarInt();
        if (messageId < 0) {
            throw new ProtocolException("negative message id " + messageId);
        }
        int requestId = in.readVarInt();
        if (requestId < 0) {
            throw new ProtocolException("negative request id " + requestId);
        }
        return new FrameHeader(new ProtocolVersion(major, minor), messageId, requestId);
    }
}
