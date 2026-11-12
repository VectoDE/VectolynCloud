// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import net.vectolyn.cloud.config.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivacyConfigLoaderTest {

    private static PrivacyConfig parse(String yaml) throws ConfigException {
        return PrivacyConfigLoader.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void missingFileGivesNinetyDays(@TempDir Path home) throws Exception {
        assertEquals(90, PrivacyConfigLoader.load(home).ipBans().retentionDays());
    }

    @Test
    void retentionIsConfigurable() throws Exception {
        assertEquals(30, parse("schema-version: 1\nip-bans:\n  retention-days: 30\n").ipBans().retentionDays());
    }

    @Test
    void outOfRangeUnknownAndTypeErrorsAreRejected() {
        assertThrows(ConfigException.class, () -> parse("schema-version: 1\nip-bans:\n  retention-days: 0\n"));
        assertThrows(ConfigException.class, () -> parse("schema-version: 1\nip-bans:\n  retention-days: 3651\n"));
        assertThrows(ConfigException.class, () -> parse("schema-version: 1\nip-bans:\n  keep: 3\n"));
        assertThrows(ConfigException.class, () -> parse("ip-bans: {}\n"));
    }

    @Test
    void runtimeReadsTheFileAndDeletesDueEntries(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/privacy.yml"), "schema-version: 1\nip-bans:\n  retention-days: 7\n");
        try (PrivacyRuntime runtime = PrivacyRuntime.open(home, Clock.systemUTC(), "0.1.0")) {
            assertEquals(7, runtime.config().ipBans().retentionDays());
            runtime.ipBans().ban("192.0.2.1", "test", null);
            runtime.start();
            runtime.start();
            assertEquals(1, runtime.ipBans().list().size());
        }
    }
}
