package net.vectolyn.tools.releasebanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseVersionTest {

    @ParameterizedTest
    @CsvSource({
        "v0.2.0-alpha, ALPHA",
        "v0.2.0-alpha.1, ALPHA",
        "v0.2.0-beta, BETA",
        "v0.2.0-beta.2, BETA",
        "v1.0.0-rc.1, RC",
        "v1.0.0-rc, RC",
        "v0.2.0-dev.3, PRE_RELEASE",
        "v0.3.0-nightly.20261030, PRE_RELEASE",
        "v1.0.0-alpha.beta, PRE_RELEASE",
        "v1.0.0-beta.2.1, PRE_RELEASE",
        "v1.0.0-RC.1, PRE_RELEASE",
        "v1.0.0-0.3.7, PRE_RELEASE",
    })
    void badgeFollowsThePrereleasePart(String tag, Badge badge) {
        assertEquals(Optional.of(badge), ReleaseVersion.parse(tag).badge());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v0.2.0", "v1.0.0", "v10.12.3"})
    void stableReleasesHaveNoBadge(String tag) {
        ReleaseVersion v = ReleaseVersion.parse(tag);
        assertTrue(v.stable());
        assertEquals(Optional.empty(), v.badge());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "0.2.0", "V0.2.0", "v0.2", "v0.2.0.1", "v0.2.0-", "v0.2.0+", "v0.2.0-beta..1", "v0.2.0-.1",
        "v0.2.0-beta.", "v0.2.0+a..b", "v0.2.0-beta_1", "v0.2.0 ", " v0.2.0", "v0.2.0-beta/1", "v0.2.0\n",
        "v0.2.0-ß", "v-1.2.3", "v1.2.3-<b>", "v1.2.3-‮beta",
    })
    void rejectsAnythingElse(String tag) {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
    }

    @Test
    void rejectsMissingAndOverlongTags() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(null));
        String longTag = "v1.0.0-" + "a".repeat(ReleaseVersion.MAX_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(longTag));
    }

    /** '+' may be rewritten in asset names, and a hyphen in the build part would look like a pre-release. */
    @ParameterizedTest
    @ValueSource(strings = {"v1.0.0+build.7", "v1.0.0+20261030.abc-def", "v1.0.0-rc.1+build.7", "v10.12.3-beta.12+b.1"})
    void rejectsBuildMetadata(String tag) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
        assertTrue(e.getMessage().contains("no +<build> metadata"), e.getMessage());
    }

    @Test
    void coreEndsBeforePrerelease() {
        assertEquals("v10.12.3".length(), ReleaseVersion.parse("v10.12.3-beta.12").coreLength());
        assertEquals("v0.2.0".length(), ReleaseVersion.parse("v0.2.0").coreLength());
        assertEquals("0.2.0-rc.1", ReleaseVersion.parse("v0.2.0-rc.1").withoutPrefix());
        assertFalse(ReleaseVersion.parse("v0.2.0-rc.1").stable());
    }

    @Test
    void stableHasNoBadgeToChooseFrom() {
        assertThrows(IllegalArgumentException.class, () -> Badge.forPrerelease(""));
    }

    @Test
    void badgeColoursMatchTheBrandSpec() {
        assertEquals(0xFF6B6B, Badge.ALPHA.fill().getRGB() & 0xFFFFFF);
        assertEquals(0xFFD23F, Badge.BETA.fill().getRGB() & 0xFFFFFF);
        assertEquals(0x00E5FF, Badge.RC.fill().getRGB() & 0xFFFFFF);
        assertEquals(0x9EA3B8, Badge.PRE_RELEASE.fill().getRGB() & 0xFFFFFF);
        assertEquals(0x14111F, Badge.TEXT.getRGB() & 0xFFFFFF);
        assertEquals("PRE-RELEASE", Badge.PRE_RELEASE.label());
    }
}
