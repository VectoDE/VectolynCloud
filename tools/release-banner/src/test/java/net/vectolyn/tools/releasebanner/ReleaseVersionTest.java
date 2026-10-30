package net.vectolyn.tools.releasebanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseVersionTest {

    /** The owner's version rules of 2026-10-30, one row per allowed form. */
    @ParameterizedTest
    @CsvSource({
        "v0.2.0-dev, DEV, PRE_RELEASE, true",
        "v0.2.0-dev.3, DEV, PRE_RELEASE, true",
        "v0.0.1-dev.0, DEV, PRE_RELEASE, true",
        "v1.1.0-dev, DEV, PRE_RELEASE, true",
        "v1.0.0-alpha, ALPHA, ALPHA, true",
        "v1.0.0-alpha.1, ALPHA, ALPHA, true",
        "v1.0.0-beta.2, BETA, BETA, true",
        "v1.0.0-rc.1, RC, RC, true",
        "v2.0.0-rc, RC, RC, true",
        "v10.12.3-beta.12, BETA, BETA, true",
        "v1.4.0-lts, LTS, LTS, false",
        "v1.4.7-lts, LTS, LTS, false",
    })
    void allowedSuffixes(String tag, ReleaseChannel channel, Badge badge, boolean prerelease) {
        ReleaseVersion v = ReleaseVersion.parse(tag);
        assertEquals(channel, v.channel());
        assertEquals(Optional.of(badge), v.badge());
        assertEquals(prerelease, v.prerelease());
        assertFalse(v.stable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.0.0", "v1.2.3", "v10.12.3"})
    void stableReleasesHaveNoBadgeAndAreNoPrerelease(String tag) {
        ReleaseVersion v = ReleaseVersion.parse(tag);
        assertTrue(v.stable());
        assertEquals(ReleaseChannel.STABLE, v.channel());
        assertFalse(v.prerelease());
        assertEquals(Optional.empty(), v.badge());
    }

    /** Below 1.0.0 only -dev: no stable release and no other suffix. */
    @ParameterizedTest
    @ValueSource(strings = {"v0.2.0", "v0.0.1", "v0.2.0-alpha.1", "v0.2.0-alpha", "v0.2.0-beta.2", "v0.2.0-rc.1",
        "v0.9.9-lts"})
    void majorZeroIsDevOnly(String tag) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
        assertTrue(e.getMessage().contains("a 0.x version may only be released with -dev"), e.getMessage());
        assertTrue(e.getMessage().contains("start at v1.0.0"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.0.0-nightly.20261030", "v1.0.0-alpha.beta", "v1.0.0-beta.2.1", "v1.0.0-RC.1",
        "v1.0.0-0.3.7", "v1.0.0-pre", "v1.0.0-dev.01", "v1.0.0-alpha.", "v1.0.0-beta..1", "v1.0.0-.1",
        "v1.0.0-devel", "v1.0.0-lts.dev", "v1.0.0-dev-1", "v0.2.0-nightly"})
    void unknownSuffixesAreRejected(String tag) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
        assertTrue(e.getMessage().contains("unknown suffix"), e.getMessage());
        assertTrue(e.getMessage().contains("allowed:"), e.getMessage());
    }

    /** An LTS line counts its releases with the patch version: v1.4.0-lts, v1.4.1-lts. */
    @ParameterizedTest
    @ValueSource(strings = {"v1.4.0-lts.1", "v1.4.0-lts.2", "v1.4.0-lts.0"})
    void ltsTakesNoCounter(String tag) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
        assertTrue(e.getMessage().contains("-lts takes no counter"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "0.2.0", "1.0.0", "V1.0.0", "v1.0", "v1.0.0.1", "v1.0.0-", "v1.0.0-beta_1", "v1.0.0 ", " v1.0.0",
        "v1.0.0-beta/1", "v1.0.0\n", "v1.0.0-ß", "v-1.2.3", "v1.2.3-<b>", "v1.2.3-‮beta", "v01.0.0", "v1.00.0",
        "v1.0.00", "vv1.0.0", "v1.0.0-dev ", "v1.0.0--dev",
    })
    void rejectsAnythingElse(String tag) {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
    }

    @Test
    void malformedTagsNameTheRules() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse("v1.0"));
        assertTrue(e.getMessage().startsWith("not a release tag: v1.0;"), e.getMessage());
        assertTrue(e.getMessage().contains(ReleaseVersion.RULES), e.getMessage());
    }

    @Test
    void rejectsMissingAndOverlongTags() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(null));
        String longTag = "v1.0.0-" + "a".repeat(ReleaseVersion.MAX_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(longTag));
    }

    /** '+' may be rewritten in asset names. */
    @ParameterizedTest
    @ValueSource(strings = {"v1.0.0+build.7", "v1.0.0+20261030.abc-def", "v1.0.0-rc.1+build.7", "v10.12.3-beta.12+b.1",
        "v0.2.0-dev+1", "v1.0.0+"})
    void rejectsBuildMetadata(String tag) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(tag));
        assertTrue(e.getMessage().contains("has build metadata"), e.getMessage());
        assertTrue(e.getMessage().contains("no +<build> metadata"), e.getMessage());
    }

    @Test
    void controlCharactersInTheTagAreEscapedInTheMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReleaseVersion.parse("v1.0.0-x\u001b[31m"));
        assertFalse(e.getMessage().contains("\u001b"), e.getMessage());
    }

    @Test
    void coreEndsBeforeTheSuffix() {
        assertEquals("v10.12.3".length(), ReleaseVersion.parse("v10.12.3-beta.12").coreLength());
        assertEquals("v1.0.0".length(), ReleaseVersion.parse("v1.0.0").coreLength());
        assertEquals("1.0.0-rc.1", ReleaseVersion.parse("v1.0.0-rc.1").withoutPrefix());
    }

    @Test
    void stableHasNoBadgeToChooseFrom() {
        assertThrows(IllegalArgumentException.class, () -> Badge.forChannel(ReleaseChannel.STABLE));
    }

    @Test
    void channelIdsAreTheSuffixes() {
        assertEquals("dev", ReleaseChannel.DEV.id());
        assertEquals("lts", ReleaseChannel.LTS.id());
        assertEquals("stable", ReleaseChannel.STABLE.id());
    }

    @Test
    void badgeColoursMatchTheBrandSpec() throws IOException {
        assertEquals(0x9EA3B8, Badge.PRE_RELEASE.fill().getRGB() & 0xFFFFFF);
        assertEquals(0xFF6B6B, Badge.ALPHA.fill().getRGB() & 0xFFFFFF);
        assertEquals(0xFFD23F, Badge.BETA.fill().getRGB() & 0xFFFFFF);
        assertEquals(0x00E5FF, Badge.RC.fill().getRGB() & 0xFFFFFF);
        assertEquals(0x5BD98A, Badge.LTS.fill().getRGB() & 0xFFFFFF);
        assertEquals(0x14111F, Badge.TEXT.getRGB() & 0xFFFFFF);
        assertEquals("PRE-RELEASE", Badge.PRE_RELEASE.label());

        // Same labels, colours and order as branding/release-banner-spec.json (written by gen_v3.py).
        String spec = Files.readString(Path.of(System.getProperty("banner.spec")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"label\": \"([^\"]+)\",\\s*\"fill\": \"#([0-9A-F]{6})\",\\s*\"text\": \"#14111F\"")
                .matcher(spec);
        List<String> fromSpec = new ArrayList<>();
        while (m.find()) {
            fromSpec.add(m.group(1) + " " + m.group(2));
        }
        List<String> fromCode = new ArrayList<>();
        for (Badge b : Badge.values()) {
            fromCode.add(b.label() + " " + String.format("%06X", b.fill().getRGB() & 0xFFFFFF));
        }
        assertEquals(fromCode, fromSpec);
    }

    /** Each badge rule of the spec accepts exactly the suffixes the parser maps to that badge. */
    @Test
    void specRulesMatchTheParser() throws IOException {
        String spec = Files.readString(Path.of(System.getProperty("banner.spec")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"match\": \"([^\"]+)\",\\s*\"label\": \"([^\"]+)\"").matcher(spec);
        int rules = 0;
        while (m.find()) {
            Pattern rule = Pattern.compile(m.group(1).replace("\\\\", "\\"));
            String label = m.group(2);
            rules++;
            for (String suffix : List.of("dev", "dev.3", "alpha", "alpha.1", "beta.2", "rc", "rc.10", "lts",
                    "lts.1", "nightly", "dev.01", "RC.1")) {
                Optional<Badge> parsed;
                try {
                    parsed = ReleaseVersion.parse("v1.0.0-" + suffix).badge();
                } catch (IllegalArgumentException e) {
                    parsed = Optional.empty();
                }
                boolean parserSaysThisBadge = parsed.map(b -> b.label().equals(label)).orElse(false);
                assertEquals(parserSaysThisBadge, rule.matcher(suffix).matches(), label + " / " + suffix);
            }
        }
        assertEquals(Badge.values().length, rules);
    }
}
