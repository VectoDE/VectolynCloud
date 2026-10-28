package net.vectora.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Variable-length encoding of 32-bit integers (7 data bits per byte, high bit set while more
 * bytes follow, at most five bytes), as used for length prefixes.
 *
 * <p>Placeholder from the stage-2 scaffold: it proves the fuzz-test setup and is replaced or
 * extended when the protocol itself is written.
 */
public final class VarInts {

    /** Largest number of bytes a 32-bit VarInt can take. */
    public static final int MAX_BYTES = 5;

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
     * Reads a VarInt at the buffer's position.
     *
     * @throws ProtocolException if the input ends early or the value is longer than
     *     {@link #MAX_BYTES} bytes
     */
    public static int read(ByteBuffer buffer) {
        int result = 0;
        for (int i = 0; i < MAX_BYTES; i++) {
            byte current;
            try {
                current = buffer.get();
            } catch (BufferUnderflowException e) {
                throw new ProtocolException("VarInt ends after " + i + " bytes", e);
            }
            result |= (current & 0x7F) << (7 * i);
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new ProtocolException("VarInt is longer than " + MAX_BYTES + " bytes");
    }
}
