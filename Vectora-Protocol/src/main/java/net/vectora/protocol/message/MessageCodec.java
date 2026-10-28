package net.vectora.protocol.message;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import net.vectora.protocol.Frame;
import net.vectora.protocol.FrameCodec;
import net.vectora.protocol.IncompatibleVersionException;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;

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
        if (!frame.version().isCompatibleWith(Protocol.PROTOCOL_VERSION)) {
            throw new IncompatibleVersionException(frame.version());
        }
        MessageType<?> type = registry.byId(frame.messageId())
                .orElseThrow(() -> new ProtocolException("unknown message id " + frame.messageId()));
        PacketReader in = new PacketReader(frame.payload());
        Message message = type.read(in);
        in.expectEnd();
        try {
            return new Envelope(frame.requestId(), message);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage(), e);
        }
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
        return fromFrame(FrameCodec.decode(bytes));
    }

    /**
     * Decodes the next message of a stream, see {@link FrameCodec#tryDecode}.
     *
     * @throws ProtocolException if the bytes can never become a valid message
     */
    public Optional<Envelope> tryDecode(ByteBuffer in) {
        return FrameCodec.tryDecode(in).map(this::fromFrame);
    }
}
