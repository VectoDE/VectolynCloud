// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The example setup files that ship with the release ({@code cloud/dist/setup.example.yml}, which
 * the release attaches as {@code setup.example.yml}, and the one for Docker Compose) must stay
 * valid under the strict reader.
 */
class ExampleSetupFilesTest {

    // The tests of a module run in its folder: cloud/setup.
    private static final Path DIST = Path.of("..", "dist");

    @Test
    void theReleaseExampleParsesAndLeavesTheEulaToTheReader() throws SetupException {
        SetupFile file = SetupFile.read(DIST.resolve("setup.example.yml"));
        assertEquals(SetupFile.SCHEMA_VERSION, file.schemaVersion());
        assertFalse(Boolean.TRUE.equals(file.eula()), "the example must not accept the EULA for the reader");
        assertNotNull(file.networkName());
    }

    @Test
    void theComposeExampleParses() throws SetupException {
        SetupFile file = SetupFile.read(DIST.resolve("docker").resolve("setup.example.yml"));
        assertEquals(SetupFile.SCHEMA_VERSION, file.schemaVersion());
    }
}
