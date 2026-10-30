package net.vectolyn.tools.releasebanner;

import java.util.Locale;

/**
 * The kind of release a tag stands for, taken from its suffix (docs/cloud/operations.md#versions-and-tags):
 *
 * <ul>
 *   <li>{@code -dev[.N]}: development build, the only kind allowed for 0.x versions</li>
 *   <li>{@code -alpha[.N]}, {@code -beta[.N]}, {@code -rc[.N]}: test releases from 1.0.0 on</li>
 *   <li>{@code -lts}: a release of a long-term support line from 1.0.0 on, without a counter</li>
 *   <li>no suffix: a stable release from 1.0.0 on</li>
 * </ul>
 *
 * <p>DEV, ALPHA, BETA and RC are GitHub pre-releases; LTS and STABLE are not.
 */
public enum ReleaseChannel {
    DEV(true),
    ALPHA(true),
    BETA(true),
    RC(true),
    LTS(false),
    STABLE(false);

    private final boolean prerelease;

    ReleaseChannel(boolean prerelease) {
        this.prerelease = prerelease;
    }

    /** True if the GitHub release is marked as a pre-release. */
    public boolean prerelease() {
        return prerelease;
    }

    /** The suffix keyword ({@code dev}, ..., {@code lts}); {@code stable} for a release without suffix. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the suffix may carry a counter {@code .N}. */
    boolean takesCounter() {
        return this != LTS && this != STABLE;
    }

    /** Whether a 0.x version may use this channel: only {@code -dev}. */
    boolean allowedBelowOne() {
        return this == DEV;
    }
}
