package net.vectolyn.tools.releasebanner;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release tag such as {@code v1.2.3} or {@code v0.3.0-beta.2}: a SemVer 2.0.0 version with a
 * leading {@code v}, as the release workflow runs on {@code v*} tags. SemVer build metadata
 * ({@code +build.7}) is rejected: GitHub may rewrite the {@code +} in release asset names, and the
 * workflow's pre-release check ({@code *-*}) would misfire on a hyphen inside the build part.
 *
 * @param tag the tag exactly as given, shown on the banner
 * @param prerelease the SemVer pre-release part without the hyphen, empty for a stable release
 * @param coreLength length of {@code vMAJOR.MINOR.PATCH}; only the points inside it are gold
 */
public record ReleaseVersion(String tag, String prerelease, int coreLength) {

    /**
     * Same as the SemVer grammar for the characters, a little looser for leading zeros, and
     * without build metadata.
     */
    private static final Pattern TAG = Pattern.compile(
            "^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");

    /** Longer tags would not fit the banner even at the smallest version size. */
    static final int MAX_LENGTH = 64;

    public ReleaseVersion {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(prerelease, "prerelease");
    }

    /**
     * Parses a release tag.
     *
     * @throws IllegalArgumentException if the tag is not {@code v} followed by a SemVer version
     *     without build metadata
     */
    public static ReleaseVersion parse(String tag) {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("release version is missing (expected a tag such as v1.2.3)");
        }
        if (tag.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("release version is longer than " + MAX_LENGTH + " characters");
        }
        Matcher m = TAG.matcher(tag);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a release tag of the form v<MAJOR>.<MINOR>.<PATCH>[-<pre-release>]"
                    + " (no +<build> metadata): " + Text.printable(tag));
        }
        String pre = m.group(4) == null ? "" : m.group(4);
        // SemVer: no empty identifiers ("1.0.0-beta..1", "1.0.0-.1", "1.0.0-beta.").
        if (!pre.isEmpty() && hasEmptyIdentifier(pre)) {
            throw new IllegalArgumentException("empty pre-release identifier in " + tag);
        }
        return new ReleaseVersion(tag, pre, m.end(3));
    }

    private static boolean hasEmptyIdentifier(String identifiers) {
        return identifiers.startsWith(".") || identifiers.endsWith(".") || identifiers.contains("..");
    }

    /** True for {@code v1.2.3}, false for anything with a pre-release part. */
    public boolean stable() {
        return prerelease.isEmpty();
    }

    /** The badge next to the wordmark; empty for a stable release. */
    public Optional<Badge> badge() {
        return stable() ? Optional.empty() : Optional.of(Badge.forPrerelease(prerelease));
    }

    /** Tag without the leading {@code v}, as used in the release file names. */
    public String withoutPrefix() {
        return tag.substring(1);
    }
}
