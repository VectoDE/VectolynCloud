package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VarIntsTest {

    @Test
    void encodesKnownValues() {
        assertArrayEquals(new byte[] {0x00}, encode(0));
        assertArrayEquals(new byte[] {0x7F}, encode(127));
        assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, encode(128));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07},
                encode(Integer.MAX_VALUE));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F},
                encode(-1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
    void roundTrips(int value) {
        byte[] bytes = encode(value);
        assertEquals(VarInts.size(value), bytes.length);
        assertEquals(value, VarInts.read(ByteBuffer.wrap(bytes)));
    }

    @Test
    void rejectsTruncatedInput() {
        assertThrows(ProtocolException.class, () -> VarInts.read(ByteBuffer.wrap(new byte[] {(byte) 0x80})));
        assertThrows(ProtocolException.class, () -> VarInts.read(ByteBuffer.allocate(0)));
    }

    @Test
    void rejectsMoreThanFiveBytes() {
        byte[] tooLong = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
        assertThrows(ProtocolException.class, () -> VarInts.read(ByteBuffer.wrap(tooLong)));
    }

    @Test
    void rejectsBitsBeyondThirtyTwo() {
        byte[] overflow = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x1F};
        assertThrows(ProtocolException.class, () -> VarInts.read(ByteBuffer.wrap(overflow)));
    }

    @Test
    void rejectsNonMinimalEncodings() {
        assertThrows(ProtocolException.class, () -> VarInts.read(ByteBuffer.wrap(new byte[] {(byte) 0x80, 0x00})));
        assertThrows(ProtocolException.class,
                () -> VarInts.read(ByteBuffer.wrap(new byte[] {(byte) 0x81, (byte) 0x80, 0x00})));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 127, 128, Integer.MAX_VALUE, 1L << 35, Long.MAX_VALUE, Long.MIN_VALUE, -1})
    void longsRoundTrip(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_LONG_BYTES);
        VarInts.writeLong(buffer, value);
        assertEquals(VarInts.sizeOfLong(value), buffer.position());
        assertEquals(value, VarInts.readLong(buffer.flip()));
    }

    @Test
    void longOfMinusOneTakesTenBytes() {
        assertEquals(VarInts.MAX_LONG_BYTES, VarInts.sizeOfLong(-1L));
    }

    @Test
    void rejectsMalformedLongs() {
        byte[] overflow = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x03};
        assertThrows(ProtocolException.class, () -> VarInts.readLong(ByteBuffer.wrap(overflow)));
        assertThrows(ProtocolException.class, () -> VarInts.readLong(ByteBuffer.wrap(new byte[] {(byte) 0x80, 0x00})));
        assertThrows(ProtocolException.class, () -> VarInts.readLong(ByteBuffer.wrap(new byte[] {(byte) 0x80})));
    }

    private static byte[] encode(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(buffer, value);
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }
}
