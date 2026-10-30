package net.vectolyn.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Builds a message payload in memory. Multi-byte numbers are big-endian; strings, byte arrays
 * and lists carry a VarInt length prefix and must stay within the limit the caller passes.
 *
 * <p>Invalid arguments (a string over its limit, a byte value above 255) are programming errors
 * and throw {@link IllegalArgumentException}. Growing beyond {@link Protocol#MAX_FRAME_SIZE}
 * throws {@link ProtocolException}, because such a payload could never be sent.
 *
 * <p>Not thread-safe; use one writer per message.
 */
public final class PacketWriter {

    private static final int INITIAL_CAPACITY = 64;

    private byte[] bytes = new byte[INITIAL_CAPACITY];
    private int size;

    /** Creates an empty writer. */
    public PacketWriter() {
    }

    /** Returns the number of bytes written so far. */
    public int size() {
        return size;
    }

    /** Returns a copy of the bytes written so far. */
    public byte[] toByteArray() {
        return Arrays.copyOf(bytes, size);
    }

    /** Writes one byte, {@code value} must be 0 to 255. */
    public PacketWriter writeByte(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("byte value must be 0 to 255, got " + value);
        }
        ensure(1);
        bytes[size++] = (byte) value;
        return this;
    }

    /** Writes {@code true} as 1 and {@code false} as 0. */
    public PacketWriter writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    /** Writes an unsigned 16-bit number, {@code value} must be 0 to 65535. */
    public PacketWriter writeShort(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("unsigned short must be 0 to 65535, got " + value);
        }
        return put(ByteBuffer.allocate(Short.BYTES).putShort((short) value));
    }

    /** Writes a 32-bit number, four bytes. */
    public PacketWriter writeInt(int value) {
        return put(ByteBuffer.allocate(Integer.BYTES).putInt(value));
    }

    /** Writes a 64-bit number, eight bytes. */
    public PacketWriter writeLong(long value) {
        return put(ByteBuffer.allocate(Long.BYTES).putLong(value));
    }

    /** Writes the IEEE 754 bits of {@code value}, eight bytes. */
    public PacketWriter writeDouble(double value) {
        return put(ByteBuffer.allocate(Double.BYTES).putDouble(value));
    }

    /** Writes a VarInt, one to five bytes. */
    public PacketWriter writeVarInt(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(buffer, value);
        return put(buffer);
    }

    /** Writes a VarLong, one to ten bytes. */
    public PacketWriter writeVarLong(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_LONG_BYTES);
        VarInts.writeLong(buffer, value);
        return put(buffer);
    }

    /** Writes a UUID as two 64-bit numbers, most significant first. */
    public PacketWriter writeUuid(UUID value) {
        Objects.requireNonNull(value, "value");
        return writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits());
    }

    /**
     * Writes a UTF-8 string with a VarInt byte-length prefix.
     *
     * @param maxBytes largest allowed length in UTF-8 bytes
     * @throws IllegalArgumentException if the string is longer than {@code maxBytes} bytes or
     *     contains an unpaired surrogate
     */
    public PacketWriter writeString(String value, int maxBytes) {
        Objects.requireNonNull(value, "value");
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("string is not valid Unicode (unpaired surrogate)", e);
        }
        byte[] utf8 = new byte[encoded.remaining()];
        encoded.get(utf8);
        return writeByteArray(utf8, maxBytes, "string");
    }

    /**
     * Writes a byte array with a VarInt length prefix.
     *
     * @param maxLength largest allowed length
     * @throws IllegalArgumentException if the array is longer than {@code maxLength}
     */
    public PacketWriter writeByteArray(byte[] value, int maxLength) {
        Objects.requireNonNull(value, "value");
        return writeByteArray(value, maxLength, "byte array");
    }

    /** Writes the bytes as they are, without a length prefix. */
    public PacketWriter writeBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        ensure(value.length);
        System.arraycopy(value, 0, bytes, size, value.length);
        size += value.length;
        return this;
    }

    /**
     * Writes a list with a VarInt element-count prefix.
     *
     * @param maxSize largest allowed number of elements
     * @param element writes one element
     * @throws IllegalArgumentException if the list has more than {@code maxSize} elements
     */
    public <T> PacketWriter writeList(List<T> values, int maxSize, BiConsumer<PacketWriter, ? super T> element) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(element, "element");
        if (values.size() > maxSize) {
            throw new IllegalArgumentException("list has " + values.size() + " elements, limit " + maxSize);
        }
        writeVarInt(values.size());
        for (T value : values) {
            element.accept(this, value);
        }
        return this;
    }

    private PacketWriter writeByteArray(byte[] value, int maxLength, String what) {
        if (value.length > maxLength) {
            throw new IllegalArgumentException(what + " has " + value.length + " bytes, limit " + maxLength);
        }
        return writeVarInt(value.length).writeBytes(value);
    }

    private PacketWriter put(ByteBuffer filled) {
        filled.flip();
        int length = filled.remaining();
        ensure(length);
        filled.get(bytes, size, length);
        size += length;
        return this;
    }

    private void ensure(int additional) {
        if (additional > Protocol.MAX_FRAME_SIZE - size) {
            throw new ProtocolException("payload would exceed the frame limit of " + Protocol.MAX_FRAME_SIZE + " bytes");
        }
        int needed = size + additional;
        if (needed > bytes.length) {
            int grown = (int) Math.min(Protocol.MAX_FRAME_SIZE, Math.max((long) bytes.length * 2, needed));
            bytes = Arrays.copyOf(bytes, grown);
        }
    }
}
