// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
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
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import net.vectolyn.cloud.config.model.LogsConfig;
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
    void eachStartBeginsANewLogAndOldLogsOfEveryServiceAreDeleted(@TempDir Path dir) throws Exception {
        Instant now = Instant.parse("2026-11-12T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        // A service that no longer exists: its old log goes when any service starts.
        Files.writeString(dir.resolve("removed-1-2026-10-01.log"), "old");
        Files.writeString(dir.resolve("removed-1-2026-11-10.log"), "recent");
        for (int run = 1; run <= 3; run++) {
            try (Writer w = ServiceOutput.openLog(dir, "lobby-1", LogsConfig::defaults, clock)) {
                w.write("run " + run + "\n");
            }
            Files.setLastModifiedTime(dir.resolve("lobby-1.log"), FileTime.from(now));
        }
        assertEquals("run 3\n", Files.readString(dir.resolve("lobby-1.log")));
        assertEquals("run 1\n", Files.readString(dir.resolve("lobby-1-2026-11-12.log")));
        assertEquals("run 2\n", Files.readString(dir.resolve("lobby-1-2026-11-12.1.log")));
        assertFalse(Files.exists(dir.resolve("removed-1-2026-10-01.log")));
        assertTrue(Files.exists(dir.resolve("removed-1-2026-11-10.log")));
    }
}
