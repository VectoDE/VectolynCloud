package net.vectolyn.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.Arrays;

/** Fuzz targets for the length-prefixed reads of {@link PacketReader}. */
class PacketReaderFuzzTest {

    private static final int LIMIT = 256;

    /** Strings: only ProtocolException, and an accepted string re-encodes to the same bytes. */
    @FuzzTest
    void readStringFailsOnlyWithProtocolException(byte @NotNull [] input) {
        PacketReader in = new PacketReader(input);
        String value;
        try {
            value = in.readString(LIMIT);
        } catch (ProtocolException expected) {
            return;
        }
        int consumed = input.length - in.remaining();
        byte[] again = new PacketWriter().writeString(value, LIMIT).toByteArray();
        assertArrayEquals(Arrays.copyOf(input, consumed), again);
    }

    /** Byte arrays and lists: only ProtocolException, and never more elements than the limit. */
    @FuzzTest
    void readListFailsOnlyWithProtocolException(byte @NotNull [] input) {
        try {
            int size = new PacketReader(input).readList(8, reader -> reader.readByteArray(16)).size();
            assertTrue(size <= 8);
        } catch (ProtocolException expected) {
            // Malformed input is fine as long as it is reported this way.
        }
    }
}
