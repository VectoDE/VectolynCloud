package net.vectolyn.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads a message payload written by {@link PacketWriter}.
 *
 * <p>Every read checks its input. Running out of bytes, a negative or too large length, invalid
 * UTF-8, a boolean other than 0 or 1 or an overlong VarInt all throw {@link ProtocolException},
 * and nothing else is thrown for bad input. Every length-prefixed read takes an upper limit from
 * the caller, so a peer cannot make the reader allocate more than that.
 *
 * <p>Not thread-safe; use one reader per message.
 */
public final class PacketReader {

    private final ByteBuffer buffer;

    /** Reads from {@code bytes}. The array is not copied and must not change while reading. */
    public PacketReader(byte[] bytes) {
        this(ByteBuffer.wrap(bytes));
    }

    /**
     * Reads the remaining bytes of {@code buffer}. The reader works on its own view, so the
     * position of {@code buffer} does not move.
     */
    public PacketReader(ByteBuffer buffer) {
        this.buffer = buffer.slice().order(ByteOrder.BIG_ENDIAN);
    }

    /** Returns how many bytes are left. */
    public int remaining() {
        return buffer.remaining();
    }

    /** Reads one signed byte. */
    public byte readByte() {
        require(1, "byte");
        return buffer.get();
    }

    /** Reads one byte as 0 to 255. */
    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    /** Reads a boolean; only 0 and 1 are valid. */
    public boolean readBoolean() {
        int value = readUnsignedByte();
        return switch (value) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new ProtocolException("boolean must be 0 or 1, got " + value);
        };
    }

    /** Reads an unsigned 16-bit number. */
    public int readUnsignedShort() {
        require(Short.BYTES, "short");
        return buffer.getShort() & 0xFFFF;
    }

    /** Reads a 32-bit number. */
    public int readInt() {
        require(Integer.BYTES, "int");
        return buffer.getInt();
    }

    /** Reads a 64-bit number. */
    public long readLong() {
        require(Long.BYTES, "long");
        return buffer.getLong();
    }

    /** Reads an IEEE 754 double. NaN and infinities are returned as they are; callers check ranges. */
    public double readDouble() {
        require(Double.BYTES, "double");
        return buffer.getDouble();
    }

    /** Reads a VarInt. */
    public int readVarInt() {
        return VarInts.read(buffer);
    }

    /** Reads a VarLong. */
    public long readVarLong() {
        return VarInts.readLong(buffer);
    }

    /** Reads a UUID written by {@link PacketWriter#writeUuid}. */
    public UUID readUuid() {
        require(2 * Long.BYTES, "UUID");
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * Reads a UTF-8 string with a VarInt byte-length prefix.
     *
     * @param maxBytes largest accepted length in UTF-8 bytes
     * @throws ProtocolException if the length is negative or above {@code maxBytes}, the input
     *     ends early or the bytes are not valid UTF-8
     */
    public String readString(int maxBytes) {
        int length = readLength(maxBytes, "string");
        ByteBuffer utf8 = buffer.slice(buffer.position(), length);
        buffer.position(buffer.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(utf8)
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("string is not valid UTF-8", e);
        }
    }

    /**
     * Reads a string and turns it into a value type. An {@link IllegalArgumentException} from
     * {@code parser} becomes a {@link ProtocolException}.
     *
     * @param maxBytes largest accepted length in UTF-8 bytes
     * @param parser validating constructor or factory of the value type
     */
    public <T> T readValidated(int maxBytes, Function<String, T> parser) {
        String raw = readString(maxBytes);
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage(), e);
        }
    }

    /**
     * Reads a byte array with a VarInt length prefix.
     *
     * @param maxLength largest accepted length
     * @throws ProtocolException if the length is negative or above {@code maxLength}, or the
     *     input ends early
     */
    public byte[] readByteArray(int maxLength) {
        int length = readLength(maxLength, "byte array");
        byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }

    /** Reads all bytes that are left. Their number is bounded by the frame the reader works on. */
    public byte[] readRemainingBytes() {
        byte[] value = new byte[buffer.remaining()];
        buffer.get(value);
        return value;
    }

    /**
     * Reads a list with a VarInt element-count prefix.
     *
     * @param maxSize largest accepted number of elements
     * @param element reads one element
     * @return an unmodifiable list
     * @throws ProtocolException if the count is negative or above {@code maxSize}, or an element
     *     is invalid
     */
    public <T> List<T> readList(int maxSize, Function<PacketReader, T> element) {
        Objects.requireNonNull(element, "element");
        checkLimit(maxSize);
        int count = readVarInt();
        if (count < 0 || count > maxSize) {
            throw new ProtocolException("list has " + count + " elements, limit " + maxSize);
        }
        // Every element takes at least one byte, so a count above the remaining bytes is invalid.
        if (count > buffer.remaining()) {
            throw new ProtocolException("list announces " + count + " elements but only "
                    + buffer.remaining() + " bytes are left");
        }
        List<T> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(element.apply(this));
        }
        return Collections.unmodifiableList(values);
    }

    /** Checks that every byte has been read. */
    public void expectEnd() {
        if (buffer.hasRemaining()) {
            throw new ProtocolException(buffer.remaining() + " unexpected bytes after the message");
        }
    }

    private int readLength(int max, String what) {
        checkLimit(max);
        int length = readVarInt();
        if (length < 0) {
            throw new ProtocolException(what + " has negative length " + length);
        }
        if (length > max) {
            throw new ProtocolException(what + " has " + length + " bytes, limit " + max);
        }
        require(length, what);
        return length;
    }

    private void require(int bytes, String what) {
        if (buffer.remaining() < bytes) {
            throw new ProtocolException(what + " needs " + bytes + " bytes, only " + buffer.remaining() + " left",
                    new BufferUnderflowException());
        }
    }

    private static void checkLimit(int max) {
        if (max < 0 || max > Protocol.MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("limit must be 0 to " + Protocol.MAX_FRAME_SIZE + ", got " + max);
        }
    }
}
