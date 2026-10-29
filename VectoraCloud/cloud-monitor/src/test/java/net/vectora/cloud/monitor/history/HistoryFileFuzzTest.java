package net.vectora.cloud.monitor.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for {@link HistoryFile#decode}: any bytes either fail with
 * {@link HistoryFile.FormatException} or decode into content within the limits that encodes and
 * decodes to the same content again.
 */
class HistoryFileFuzzTest {

    @FuzzTest
    void decodeFailsOnlyWithFormatErrorsAndRoundTrips(byte @NotNull [] input) throws Exception {
        HistoryFile.Content content;
        try {
            content = HistoryFile.decode(input);
        } catch (HistoryFile.FormatException expected) {
            return;
        }
        assertTrue(content.services().size() <= HistoryFile.MAX_SERVICES);
        assertTrue(content.node().size() <= HistoryFile.MAX_SAMPLES);
        assertEquals(content, HistoryFile.decode(HistoryFile.encode(content)));
    }
}
