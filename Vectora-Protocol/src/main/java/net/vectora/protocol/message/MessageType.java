package net.vectora.protocol.message;

import java.util.Objects;
import java.util.function.Function;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.ProtocolVersion;

/**
 * Entry of the {@link MessageRegistry}: wire id, record class, decoder and the protocol version
 * that introduced one message type.
 *
 * @param id wire id, 0 or more
 * @param type record class
 * @param reader decoder, usually the record's static {@code read} method
 * @param since the first protocol version with this message; a connection that negotiated an
 *     older minor version never carries it
 * @param <T> the message type
 */
public record MessageType<T extends Message>(int id, Class<T> type, Function<PacketReader, T> reader,
                                             ProtocolVersion since) {

    /** The version of the first messages. */
    public static final ProtocolVersion FIRST = new ProtocolVersion(1, 0);

    /**
     * Creates an entry.
     *
     * @throws IllegalArgumentException if {@code id} is negative
     */
    public MessageType {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(since, "since");
        if (id < 0) {
            throw new IllegalArgumentException("message id must not be negative: " + id);
        }
    }

    /** Creates an entry of the first protocol version, {@link #FIRST}. */
    public MessageType(int id, Class<T> type, Function<PacketReader, T> reader) {
        this(id, type, reader, FIRST);
    }

    /**
     * Whether a connection that uses {@code version} may carry this message: the same major
     * version and a minor version no older than {@link #since()}.
     *
     * @param version the negotiated version of the connection
     * @return whether the message exists in that version
     */
    public boolean availableIn(ProtocolVersion version) {
        return version.major() == since.major() && version.compareTo(since) >= 0;
    }

    /**
     * Decodes one message of this type. It does not check for leftover bytes; {@link MessageCodec}
     * does.
     *
     * @throws ProtocolException if the payload is invalid, including values the record rejects
     */
    public T read(PacketReader in) {
        try {
            return reader.apply(in);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("invalid " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
