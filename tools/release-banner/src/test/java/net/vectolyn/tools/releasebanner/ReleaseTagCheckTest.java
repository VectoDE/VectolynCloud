package net.vectolyn.tools.releasebanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseTagCheckTest {

    @TempDir
    Path tmp;

    /** What release.yml reads: the GitHub pre-release flag and the channel. */
    @ParameterizedTest
    @CsvSource({
        "v0.2.0-dev, dev, true",
        "v0.2.0-dev.3, dev, true",
        "v1.0.0-alpha.1, alpha, true",
        "v1.0.0-beta.2, beta, true",
        "v1.0.0-rc.1, rc, true",
        "v1.4.0-lts, lts, false",
        "v1.0.0, stable, false",
    })
    void writesChannelAndPrereleaseFlag(String tag, String channel, String prerelease) throws IOException {
        Path out = tmp.resolve("result.txt");
        assertEquals(0, ReleaseTagCheck.run(new String[] {"--out", out.toString()}, Map.of("RELEASE_TAG", tag)));
        assertEquals(List.of("channel=" + channel, "prerelease=" + prerelease),
                Files.readAllLines(out, StandardCharsets.UTF_8));
        // LF only, also on Windows.
        assertEquals("channel=" + channel + "\nprerelease=" + prerelease + "\n",
                Files.readString(out, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v0.2.0", "v0.2.0-alpha.1", "v1.0", "v1.0.0+build.1", "v1.0.0-nightly.1", "v1.4.0-lts.2", ""})
    void brokenRuleWritesNothing(String tag) {
        Path out = tmp.resolve("result.txt");
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseTagCheck.run(new String[] {"--out", out.toString()}, Map.of("RELEASE_TAG", tag)));
        assertFalse(Files.exists(out));
    }

    @Test
    void missingTagOrOutFails() {
        Path out = tmp.resolve("result.txt");
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseTagCheck.run(new String[] {"--out", out.toString()}, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseTagCheck.run(new String[0], Map.of("RELEASE_TAG", "v1.0.0")));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseTagCheck.run(new String[] {"--out", ""}, Map.of("RELEASE_TAG", "v1.0.0")));
        assertFalse(Files.exists(out));
    }
}
