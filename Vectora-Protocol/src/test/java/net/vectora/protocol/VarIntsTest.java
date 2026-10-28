package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

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

    private static byte[] encode(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(buffer, value);
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }
}
