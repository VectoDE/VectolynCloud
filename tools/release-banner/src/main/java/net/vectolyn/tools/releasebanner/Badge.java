package net.vectolyn.tools.releasebanner;

import java.awt.Color;

/**
 * Badge next to the wordmark, one per release channel (branding/release-banner-spec.json):
 * {@code -dev[.N]} PRE-RELEASE, {@code -alpha[.N]} ALPHA, {@code -beta[.N]} BETA, {@code -rc[.N]} RC,
 * {@code -lts} LTS. Stable releases have no badge.
 */
public enum Badge {
    PRE_RELEASE("PRE-RELEASE", 0x9EA3B8),
    ALPHA("ALPHA", 0xFF6B6B),
    BETA("BETA", 0xFFD23F),
    RC("RC", 0x00E5FF),
    /** Green of the brand's success token (color.success, dark). */
    LTS("LTS", 0x5BD98A);

    /** Text colour on every badge (Obsidian). */
    public static final Color TEXT = new Color(0x14111F);

    private final String label;
    private final Color fill;

    Badge(String label, int fill) {
        this.label = label;
        this.fill = new Color(fill);
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
     * The badge of a release channel.
     *
     * @throws IllegalArgumentException for {@link ReleaseChannel#STABLE}: stable releases have no badge
     */
    public static Badge forChannel(ReleaseChannel channel) {
        return switch (channel) {
            case DEV -> PRE_RELEASE;
            case ALPHA -> ALPHA;
            case BETA -> BETA;
            case RC -> RC;
            case LTS -> LTS;
            case STABLE -> throw new IllegalArgumentException("a stable release has no badge");
        };
    }
}
