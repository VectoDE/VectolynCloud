package net.vectora.cloud.download.archive;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fuzz target for {@link TarReader}. In a normal test run it executes in regression mode
 * (stored inputs only); the nightly workflow fuzzes it on Linux.
 */
class TarReaderFuzzTest {

    @FuzzTest
    void readFailsOnlyWithArchiveException(byte @NotNull [] input) throws IOException {
        TarReader reader = new TarReader(new ByteArrayInputStream(input));
        try {
            TarEntry entry;
            int entries = 0;
            while ((entry = reader.next()) != null) {
                entries++;
                assertTrue(entries <= input.length / TarReader.BLOCK, "more entries than header blocks");
                assertTrue(!entry.name().isEmpty(), "entry without a name");
                long read = 0;
                InputStream content = reader.content();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = content.read(buffer)) >= 0) {
                    read += n;
                }
                assertTrue(read == entry.size(), "content length differs from the header size");
            }
        } catch (ArchiveException expected) {
            // Malformed input is fine as long as it is reported this way.
        }
    }
}
