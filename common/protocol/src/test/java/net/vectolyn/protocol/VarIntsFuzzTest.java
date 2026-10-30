package net.vectolyn.protocol;

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
            // Only minimal encodings are accepted, so a value always takes exactly size(value) bytes.
            assertEquals(VarInts.size(value), buffer.position(), "accepted a non-minimal VarInt");
        } catch (ProtocolException expected) {
            // Malformed input is fine as long as it is reported this way.
        }
    }

    @FuzzTest
    void writeThenReadLongReturnsTheSameValue(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(VarInts.MAX_LONG_BYTES);
        VarInts.writeLong(buffer, value);
        assertEquals(VarInts.sizeOfLong(value), buffer.position());
        assertEquals(value, VarInts.readLong(buffer.flip()));
        assertTrue(!buffer.hasRemaining(), "read must consume exactly the written bytes");
    }

    @FuzzTest
    void readLongFailsOnlyWithProtocolException(byte @NotNull [] input) {
        ByteBuffer buffer = ByteBuffer.wrap(input);
        try {
            long value = VarInts.readLong(buffer);
            assertEquals(VarInts.sizeOfLong(value), buffer.position(), "accepted a non-minimal VarLong");
        } catch (ProtocolException expected) {
            // Malformed input is fine as long as it is reported this way.
        }
    }
}
