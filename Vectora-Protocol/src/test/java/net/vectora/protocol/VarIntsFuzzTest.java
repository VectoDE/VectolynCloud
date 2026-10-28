package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.ByteBuffer;

/**
 * Fuzz targets for {@link VarInts}. In a normal test run they execute in regression mode
 * (stored inputs only); the nightly workflow fuzzes them on Linux.
 */
class VarIntsFuzzTest {

    @FuzzTest
    void writeThenReadReturnsTheSameValue(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_BYTES);
        VarInts.write(buffer, value);
        assertEquals(VarInts.size(value), buffer.position());
        assertEquals(value, VarInts.read(buffer.flip()));
        assertTrue(!buffer.hasRemaining(), "read must consume exactly the written bytes");
    }

    @FuzzTest
    void readFailsOnlyWithProtocolException(byte @NotNull [] input) {
        ByteBuffer buffer = ByteBuffer.wrap(input);
        try {
            int value = VarInts.read(buffer);
            assertTrue(buffer.position() <= VarInts.MAX_BYTES, "read past the VarInt limit");
            assertTrue(buffer.position() >= VarInts.size(value), "read fewer bytes than the value needs");
        } catch (ProtocolException expected) {
            // Malformed input is fine as long as it is reported this way.
        }
    }
}
