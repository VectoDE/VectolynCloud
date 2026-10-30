package net.vectolyn.tools.releasebanner;

import java.awt.Color;
import java.util.regex.Pattern;

/**
 * Pre-release badge next to the wordmark. Chosen from the SemVer pre-release part of the tag
 * (branding/release-banner-spec.json): {@code alpha[.N]}, {@code beta[.N]}, {@code rc[.N]},
 * anything else is a generic pre-release. Stable releases have no badge.
 */
public enum Badge {
    ALPHA("ALPHA", 0xFF6B6B, "^alpha(\\.\\d+)?$"),
    BETA("BETA", 0xFFD23F, "^beta(\\.\\d+)?$"),
    RC("RC", 0x00E5FF, "^rc(\\.\\d+)?$"),
    PRE_RELEASE("PRE-RELEASE", 0x9EA3B8, ".+");

    /** Text colour on every badge (Obsidian). */
    public static final Color TEXT = new Color(0x14111F);

    private final String label;
    private final Color fill;
    private final Pattern match;

    Badge(String label, int fill, String match) {
        this.label = label;
        this.fill = new Color(fill);
        this.match = Pattern.compile(match);
    }

    /** Text on the badge. */
    public String label() {
        return label;
    }

    /** Background colour of the badge. */
    public Color fill() {
        return fill;
    }

    /**
     * The badge for a non-empty pre-release part ({@code beta.2} of {@code v0.3.0-beta.2}).
     *
     * @throws IllegalArgumentException for an empty pre-release part: stable releases have no badge
     */
    public static Badge forPrerelease(String prerelease) {
        if (prerelease == null || prerelease.isEmpty()) {
            throw new IllegalArgumentException("a stable release has no badge");
        }
        for (Badge badge : values()) {
            if (badge.match.matcher(prerelease).matches()) {
                return badge;
            }
        }
        throw new AssertionError("PRE_RELEASE matches every non-empty string");
    }
}
