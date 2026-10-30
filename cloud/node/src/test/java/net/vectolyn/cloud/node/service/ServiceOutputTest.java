package net.vectolyn.cloud.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.vectolyn.cloud.config.secret.RedactingFilter;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceOutputTest {

    private static List<String> pump(byte[] input, StringWriter log) {
        List<String> lines = new ArrayList<>();
        RedactingFilter filter = RedactingFilter.of(List.of(Secret.of("hunter2-token")));
        new ServiceOutput(new ByteArrayInputStream(input), log, filter, lines::add).run();
        return lines;
    }

    @Test
    void secretsAreMaskedInTheLogAndForListeners() {
        StringWriter log = new StringWriter();
        List<String> lines = pump("token hunter2-token here\r\nsecond\nlast without break"
                .getBytes(StandardCharsets.UTF_8), log);
        assertEquals(List.of("token *** here", "second", "last without break"), lines);
        assertEquals("token *** here\nsecond\nlast without break\n", log.toString());
        assertFalse(log.toString().contains("hunter2"));
    }

    @Test
    void longLinesAreSplitAndInvalidUtf8IsReplaced() {
        byte[] input = new byte[ServiceOutput.MAX_LINE_BYTES + 10];
        java.util.Arrays.fill(input, (byte) 'a');
        List<String> lines = pump(input, new StringWriter());
        assertEquals(2, lines.size());
        assertEquals(ServiceOutput.MAX_LINE_BYTES, lines.get(0).length());
        assertEquals(10, lines.get(1).length());

        List<String> broken = pump(new byte[] {'o', 'k', (byte) 0xff, '\n'}, new StringWriter());
        assertEquals(List.of("ok�"), broken);
    }

    @Test
    void logsAreRotatedAndOnlyFiveOldOnesAreKept(@TempDir Path dir) throws Exception {
        for (int run = 1; run <= 7; run++) {
            try (Writer w = ServiceOutput.openLog(dir, "lobby-1")) {
                w.write("run " + run);
            }
        }
        assertEquals("run 7", Files.readString(dir.resolve("lobby-1.log")));
        for (int i = 1; i <= ServiceOutput.KEEP; i++) {
            assertEquals("run " + (7 - i), Files.readString(dir.resolve("lobby-1." + i + ".log")));
        }
        assertFalse(Files.exists(dir.resolve("lobby-1.6.log")));
        assertTrue(Files.isDirectory(dir));
    }
}
