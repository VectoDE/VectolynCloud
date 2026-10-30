package net.vectolyn.cloud.download;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.lock.LockedArtifact;
import net.vectolyn.cloud.download.lock.Trust;
import net.vectolyn.cloud.download.provider.ArtifactCoordinate;
import net.vectolyn.cloud.download.provider.ResolvedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileNamesTest {

    private static final Checksum SHA256 = Checksum.parse(HashAlgorithm.SHA256,
            "416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15").orElseThrow();

    @ParameterizedTest
    @ValueSource(strings = {"paper-26.3-227.jar", "OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz",
        "Chunky-Fabric-1.4.40.jar", "minecraft_server.26.3.jar", "fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar",
        "a", "console.jar", "com10.jar", "Mod (fixed).jar"})
    void acceptsPlainBaseNames(String name) {
        assertTrue(FileNames.isSafeBaseName(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../paper.jar", "..\\paper.jar", "a/b.jar", "a\\b.jar", "/etc/passwd",
        "C:paper.jar", "paper.jar.", "paper.jar ", "a\u0000b", "a\nb", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b",
        "CON", "con", "con.jar", "NUL.txt", "aux.tar.gz", "prn", "com1", "COM9.jar", "lpt1.zip", "lpt0", "com¹.jar",
        "conin$", "CONOUT$.jar"})
    void refusesPathsSeparatorsAndDeviceNames(String name) {
        assertFalse(FileNames.isSafeBaseName(name), name);
        assertThrows(IllegalArgumentException.class, () -> FileNames.requireSafeBaseName(name, "fileName"));
    }

    @Test
    void refusesOverlongNames() {
        assertFalse(FileNames.isSafeBaseName("a".repeat(256)));
        assertTrue(FileNames.isSafeBaseName("a".repeat(255)));
        assertFalse(FileNames.isSafeBaseName(null));
    }

    @Test
    void resolvedArtifactRefusesAPathAsFileName() {
        ArtifactCoordinate coordinate = new ArtifactCoordinate("papermc", "paper", "26.3", "227");
        URI url = URI.create("https://fill-data.papermc.io/v1/objects/x/paper.jar");
        assertThrows(IllegalArgumentException.class, () -> new ResolvedArtifact(coordinate, url, "../../paper.jar",
                Optional.of(SHA256), OptionalLong.empty(), OptionalInt.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedArtifact(coordinate, url, "nul.jar",
                Optional.of(SHA256), OptionalLong.empty(), OptionalInt.empty(), Map.of()));
        assertDoesNotThrow(() -> new ResolvedArtifact(coordinate, url, "paper-26.3-227.jar",
                Optional.of(SHA256), OptionalLong.empty(), OptionalInt.empty(), Map.of()));
    }

    @Test
    void lockEntryRefusesAPathAsFileName() {
        ArtifactCoordinate coordinate = new ArtifactCoordinate("papermc", "paper", "26.3", "227");
        URI url = URI.create("https://fill-data.papermc.io/v1/objects/x/paper.jar");
        assertThrows(IllegalArgumentException.class, () -> new LockedArtifact(coordinate, url, "sub/paper.jar",
                SHA256, SHA256, Trust.SOURCE, 1, null));
    }
}
