// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Which Minecraft versions the assistant offers. */
class MinecraftVersionsTest {

    @Test
    void supportedAreTheReleasesFromTheOldestToTheBridgeVersionWithTheirPatches() {
        assertTrue(MinecraftVersions.supported(MinecraftVersions.OLDEST));
        assertTrue(MinecraftVersions.supported(NetworkServer.MINECRAFT_VERSION));
        assertTrue(MinecraftVersions.supported(NetworkServer.MINECRAFT_VERSION + ".1"), "a patch of the bridge version");
        assertTrue(MinecraftVersions.supported("26.2"));
        for (String version : List.of("26.4", "27.1", "25.4", "1.21.11", "1.8.8", "26", "26.3-pre1", "latest", "")) {
            assertFalse(MinecraftVersions.supported(version), version);
        }
        assertFalse(MinecraftVersions.supported(null));
    }

    @Test
    void offeredKeepsTheSupportedOnesNewestFirstWithoutRepeats() {
        assertEquals(List.of("26.3.1", "26.3", "26.2", "26.1"),
                MinecraftVersions.offered(List.of("26.1", "26.4", "26.3", "1.21.11", "26.3.1", "26.2", "26.3")));
        assertEquals("26.3.1", MinecraftVersions.newest(List.of("26.3", "26.3.1")));
    }

    @Test
    void withoutAnAnswerFromPaperTheCompiledInVersionIsOffered() {
        assertEquals(List.of(NetworkServer.MINECRAFT_VERSION), MinecraftVersions.offered(List.of()));
        assertEquals(List.of(NetworkServer.MINECRAFT_VERSION), MinecraftVersions.offered(List.of("27.1", "1.21.11")));
    }
}
