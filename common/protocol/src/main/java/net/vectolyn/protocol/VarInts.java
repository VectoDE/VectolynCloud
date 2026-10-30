package net.vectolyn.protocol;

import java.nio.ByteBuffer;

/**
 * Variable-length encoding of integers: 7 data bits per byte, least significant group first,
 * high bit set while more bytes follow. A 32-bit value takes at most five bytes, a 64-bit value
 * at most ten.
 *
 * <p>Reading is strict. Only the shortest encoding of a value is accepted, and bits beyond the
 * width of the type are an error. Every value therefore has exactly one valid encoding, which
 * keeps decoded frames byte-identical when they are encoded again.
 */
public final class VarInts {

    /** Largest number of bytes a 32-bit VarInt can take. */
    public static final int MAX_BYTES = 5;

    /** Largest number of bytes a 64-bit VarLong can take. */
    public static final int MAX_LONG_BYTES = 10;

    private VarInts() {
    }

    /** Returns how many bytes {@link #write} needs for {@code value}. */
    public static int size(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /** Returns how many bytes {@link #writeLong} needs for {@code value}. */
    public static int sizeOfLong(long value) {
        int bytes = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /**
     * Writes {@code value} at the buffer's position.
     *
     * @throws java.nio.BufferOverflowException if fewer than {@link #size(int)} bytes remain
     */
    public static void write(ByteBuffer buffer, int value) {
        while ((value & ~0x7F) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.put((byte) value);
    }

    /**
     * Writes {@code value} as a VarLong at the buffer's position.
     *
     * @throws java.nio.BufferOverflowException if fewer than {@link #sizeOfLong(long)} bytes remain
     */
    public static void writeLong(ByteBuffer buffer, long value) {
        while ((value & ~0x7FL) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.put((byte) value);
    }

    /**
     * Reads a VarInt at the buffer's position.
     *
     * @throws ProtocolException if the input ends early, the value needs more than 32 bits or
     *     is not minimally encoded
     */
    public static int read(ByteBuffer buffer) {
        int result = 0;
        for (int i = 0; i < MAX_BYTES; i++) {
            if (!buffer.hasRemaining()) {
                throw new ProtocolException("VarInt ends after " + i + " bytes");
            }
            byte current = buffer.get();
            // The fifth byte carries bits 28 to 31 only; anything above, or a continuation bit, overflows.
            if (i == MAX_BYTES - 1 && (current & 0xF0) != 0) {
                throw new ProtocolException("VarInt does not fit into 32 bits");
            }
            result |= (current & 0x7F) << (7 * i);
            if ((current & 0x80) == 0) {
                if (current == 0 && i > 0) {
                    throw new ProtocolException("VarInt is not minimally encoded");
                }
                return result;
            }
        }
        throw new ProtocolException("VarInt is longer than " + MAX_BYTES + " bytes");
    }

    /**
     * Reads a VarLong at the buffer's position.
     *
     * @throws ProtocolException if the input ends early, the value needs more than 64 bits or
     *     is not minimally encoded
     */
    public static long readLong(ByteBuffer buffer) {
        long result = 0;
        for (int i = 0; i < MAX_LONG_BYTES; i++) {
            if (!buffer.hasRemaining()) {
                throw new ProtocolException("VarLong ends after " + i + " bytes");
            }
            byte current = buffer.get();
            // The tenth byte carries bit 63 only.
            if (i == MAX_LONG_BYTES - 1 && (current & 0xFE) != 0) {
                throw new ProtocolException("VarLong does not fit into 64 bits");
            }
            result |= (long) (current & 0x7F) << (7 * i);
            if ((current & 0x80) == 0) {
                if (current == 0 && i > 0) {
                    throw new ProtocolException("VarLong is not minimally encoded");
                }
                return result;
            }
        }
        throw new ProtocolException("VarLong is longer than " + MAX_LONG_BYTES + " bytes");
    }
}
