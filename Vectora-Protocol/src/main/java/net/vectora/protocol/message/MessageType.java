package net.vectora.protocol.message;

import java.util.Objects;
import java.util.function.Function;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.ProtocolException;

/**
 * Entry of the {@link MessageRegistry}: wire id, record class and decoder of one message type.
 *
 * @param id wire id, 0 or more
 * @param type record class
 * @param reader decoder, usually the record's static {@code read} method
 * @param <T> the message type
 */
public record MessageType<T extends Message>(int id, Class<T> type, Function<PacketReader, T> reader) {

    /**
     * Creates an entry.
     *
     * @throws IllegalArgumentException if {@code id} is negative
     */
    public MessageType {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reader, "reader");
        if (id < 0) {
            throw new IllegalArgumentException("message id must not be negative: " + id);
        }
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
