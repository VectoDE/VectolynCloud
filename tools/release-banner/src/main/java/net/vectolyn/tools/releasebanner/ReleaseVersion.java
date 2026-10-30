package net.vectolyn.tools.releasebanner;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release tag such as {@code v1.2.3}, {@code v0.3.0-dev.2} or {@code v1.4.0-lts}, checked against
 * the version rules of the project (docs/cloud/operations.md#versions-and-tags). This is the one
 * place those rules live: release.yml checks every tag with it before anything is built
 * (check-tag.sh, {@link ReleaseTagCheck}), and the banner is drawn from the same parse.
 *
 * <ul>
 *   <li>{@code v<MAJOR>.<MINOR>.<PATCH>}, numbers without leading zeros (SemVer).</li>
 *   <li>Suffix {@code -dev}, {@code -alpha}, {@code -beta} or {@code -rc}, each with an optional
 *       counter {@code .N}; or {@code -lts} without a counter; or no suffix (stable).</li>
 *   <li>0.x versions only as {@code -dev}. Alpha, beta, rc, lts and stable start at 1.0.0.</li>
 *   <li>No build metadata ({@code +build.7}): GitHub may rewrite the {@code +} in release file
 *       names.</li>
 * </ul>
 *
 * @param tag the tag exactly as given, shown on the banner
 * @param channel the kind of release, from the suffix
 * @param coreLength length of {@code vMAJOR.MINOR.PATCH}; only the points inside it are gold
 */
public record ReleaseVersion(String tag, ReleaseChannel channel, int coreLength) {

    /** SemVer numbers without leading zeros, an optional suffix of SemVer characters, nothing else. */
    private static final Pattern TAG = Pattern.compile(
            "^v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?$");

    /** The allowed suffixes; the counter, if any, is a SemVer number. */
    private static final Pattern SUFFIX = Pattern.compile("^(dev|alpha|beta|rc|lts)(?:\\.(0|[1-9]\\d*))?$");

    /** Longer tags would not fit the banner even at the smallest version size. */
    static final int MAX_LENGTH = 64;

    static final String RULES = "allowed: v<MAJOR>.<MINOR>.<PATCH> with -dev[.N] (the only choice below 1.0.0),"
            + " -alpha[.N], -beta[.N], -rc[.N], -lts or no suffix (stable), all from 1.0.0 on;"
            + " no +<build> metadata";

    public ReleaseVersion {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(channel, "channel");
    }

    /**
     * Parses and checks a release tag.
     *
     * @throws IllegalArgumentException if the tag breaks one of the version rules; the message
     *     says which one and what is allowed
     */
    public static ReleaseVersion parse(String tag) {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("release version is missing (expected a tag such as v1.2.3)");
        }
        if (tag.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("release version is longer than " + MAX_LENGTH + " characters");
        }
        String shown = Text.printable(tag);
        if (tag.indexOf('+') >= 0) {
            throw new IllegalArgumentException("release tag " + shown + " has build metadata; " + RULES);
        }
        Matcher m = TAG.matcher(tag);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a release tag: " + shown + "; " + RULES);
        }
        ReleaseChannel channel = ReleaseChannel.STABLE;
        String suffix = m.group(4);
        if (suffix != null) {
            Matcher s = SUFFIX.matcher(suffix);
            if (!s.matches()) {
                throw new IllegalArgumentException("unknown suffix -" + Text.printable(suffix) + " in release tag "
                        + shown + "; " + RULES);
            }
            channel = ReleaseChannel.valueOf(s.group(1).toUpperCase(Locale.ROOT));
            if (s.group(2) != null && !channel.takesCounter()) {
                throw new IllegalArgumentException("release tag " + shown + ": -lts takes no counter,"
                        + " the patch version counts LTS releases (v1.4.1-lts); " + RULES);
            }
        }
        if ("0".equals(m.group(1)) && !channel.allowedBelowOne()) {
            throw new IllegalArgumentException("release tag " + shown + ": a 0.x version may only be released"
                    + " with -dev (v" + m.group(1) + "." + m.group(2) + "." + m.group(3) + "-dev or -dev.N);"
                    + " alpha, beta, rc, lts and stable releases start at v1.0.0");
        }
        return new ReleaseVersion(tag, channel, m.end(3));
    }

    /** True for a release without suffix ({@code v1.2.3}). */
    public boolean stable() {
        return channel == ReleaseChannel.STABLE;
    }

    /** True if GitHub marks the release as a pre-release (dev, alpha, beta, rc). */
    public boolean prerelease() {
        return channel.prerelease();
    }

    /** The badge next to the wordmark; empty for a stable release. */
    public Optional<Badge> badge() {
        return stable() ? Optional.empty() : Optional.of(Badge.forChannel(channel));
    }

    /** Tag without the leading {@code v}, as used in the release file names. */
    public String withoutPrefix() {
        return tag.substring(1);
    }
}
