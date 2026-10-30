package net.vectolyn.protocol.message;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.protocol.Frame;
import net.vectolyn.protocol.FrameCodec;
import net.vectolyn.protocol.FrameHeader;
import net.vectolyn.protocol.IncompatibleVersionException;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.Protocol;
import net.vectolyn.protocol.ProtocolException;

/**
 * Encodes {@link Envelope}s into frames and bytes, and decodes them back. This is the entry point
 * for both ends of a connection; it needs no networking library, so a transport can be Netty on
 * the cloud and plain sockets inside a server.
 *
 * <p>Decoding throws only {@link ProtocolException} for bad input. Thread-safe.
 */
public final class MessageCodec {

    private static final MessageCodec STANDARD = new MessageCodec(MessageRegistry.standard());

    private final MessageRegistry registry;

    /** Creates a codec for the messages in {@code registry}. */
    public MessageCodec(MessageRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Returns the codec for {@link MessageRegistry#standard()}. */
    public static MessageCodec standard() {
        return STANDARD;
    }

    /**
     * Builds the frame for {@code envelope}, stamped with {@link Protocol#PROTOCOL_VERSION}.
     *
     * @throws IllegalArgumentException if the message type is not registered
     */
    public Frame toFrame(Envelope envelope) {
        MessageType<?> type = registry.typeOf(envelope.message());
        PacketWriter out = new PacketWriter();
        envelope.message().write(out);
        return new Frame(Protocol.PROTOCOL_VERSION, type.id(), envelope.requestId(), out.toByteArray());
    }

    /**
     * Decodes the message in {@code frame}.
     *
     * @throws IncompatibleVersionException if the frame has another major version
     * @throws ProtocolException if the message id is unknown, the payload is invalid or has bytes
     *     left over, or the request id does not fit the message
     */
    public Envelope fromFrame(Frame frame) {
        FrameHeader header = new FrameHeader(frame.version(), frame.messageId(), frame.requestId());
        return decodeMessage(header, new PacketReader(frame.payload()));
    }

    /** Encodes {@code envelope} as a complete frame with length prefix. */
    public byte[] encode(Envelope envelope) {
        return FrameCodec.encode(toFrame(envelope));
    }

    /**
     * Decodes one complete frame that fills {@code bytes}.
     *
     * @throws ProtocolException if the bytes are not exactly one valid frame with a valid message
     */
    public Envelope decode(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        ByteBuffer body = FrameCodec.tryReadBody(in, Protocol.MAX_FRAME_SIZE)
                .orElseThrow(() -> new ProtocolException("frame is incomplete"));
        if (in.hasRemaining()) {
            throw new ProtocolException(in.remaining() + " bytes after the frame");
        }
        return fromBody(body);
    }

    /**
     * Decodes the next message of a stream with the full frame limit, see
     * {@link FrameCodec#tryDecode(ByteBuffer)}.
     *
     * @throws ProtocolException if the bytes can never become a valid message
     */
    public Optional<Envelope> tryDecode(ByteBuffer in) {
        return tryDecode(in, Protocol.MAX_FRAME_SIZE);
    }

    /**
     * Decodes the next message of a stream whose frame body may have at most {@code maxBodySize}
     * bytes. Before authentication both ends pass {@link Protocol#MAX_HANDSHAKE_FRAME_SIZE}.
     *
     * <p>The message is read straight from the bytes of {@code in}; the frame payload is not
     * copied on the way. Only the message records copy their own byte arrays.
     *
     * @throws IllegalArgumentException if {@code maxBodySize} is outside
     *     {@value FrameCodec#MIN_BODY_SIZE} to {@link Protocol#MAX_FRAME_SIZE}
     * @throws ProtocolException if the bytes can never become a valid message
     */
    public Optional<Envelope> tryDecode(ByteBuffer in, int maxBodySize) {
        return FrameCodec.tryReadBody(in, maxBodySize).map(this::fromBody);
    }

    private Envelope fromBody(ByteBuffer body) {
        PacketReader in = new PacketReader(body);
        return decodeMessage(FrameHeader.read(in), in);
    }

    private Envelope decodeMessage(FrameHeader header, PacketReader in) {
        if (!header.version().isCompatibleWith(Protocol.PROTOCOL_VERSION)) {
            throw new IncompatibleVersionException(header.version());
        }
        MessageType<?> type = registry.byId(header.messageId())
                .orElseThrow(() -> new ProtocolException("unknown message id " + header.messageId()));
        Message message = type.read(in);
        in.expectEnd();
        try {
            return new Envelope(header.requestId(), message);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage(), e);
        }
    }
}
