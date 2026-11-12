// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.apicompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a breaking change of the public API ({@code common/protocol}, {@code common/client}) is
 * allowed, by Semantic Versioning 2.0.0 (docs/api-compatibility.md).
 *
 * <ul>
 *   <li>From 1.0.0: only with a new major version (1.4.2 to 2.0.0).</li>
 *   <li>Below 1.0.0 SemVer allows anything (item 4), but a plugin built against 0.3.x must not
 *       break inside 0.3.x. So the minor version takes the place of the major: 0.3.1 to 0.4.0, or
 *       to 1.0.0.</li>
 * </ul>
 *
 * <p>The baseline is the newest release tag, the next version is {@code version} in
 * {@code gradle.properties} without {@code -SNAPSHOT}. A next version that is not above the
 * baseline counts as "not raised".
 */
public final class VersionRule {

    private static final Pattern VERSION = Pattern.compile(
            "^v?(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-[0-9A-Za-z.-]+)?$");

    private VersionRule() {
    }

    /** {@code MAJOR.MINOR.PATCH} of a version; a suffix such as {@code -dev} or {@code -SNAPSHOT} is dropped. */
    record Core(int major, int minor, int patch) {

        static Core parse(String text) {
            Matcher m = VERSION.matcher(text == null ? "" : text);
            if (!m.matches()) {
                throw new IllegalArgumentException("not a version of the form [v]MAJOR.MINOR.PATCH[-suffix]");
            }
            return new Core(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /**
     * Whether the next version may break the API of the baseline.
     *
     * @param baseline the newest release, for example {@code v0.1.1-dev}
     * @param next     the version being built, for example {@code 0.2.0-SNAPSHOT}
     * @return true if the step from baseline to next is a breaking step
     */
    public static boolean breakingChangeAllowed(String baseline, String next) {
        Core base = Core.parse(baseline);
        Core to = Core.parse(next);
        if (base.major() == 0) {
            return to.major() > 0 || to.minor() > base.minor();
        }
        return to.major() > base.major();
    }

    /**
     * The smallest version that may break the API of the baseline, for the error message.
     *
     * @param baseline the newest release
     * @return {@code 0.(minor+1).0} below 1.0.0, else {@code (major+1).0.0}
     */
    public static String nextBreakingVersion(String baseline) {
        Core base = Core.parse(baseline);
        return base.major() == 0 ? "0." + (base.minor() + 1) + ".0" : (base.major() + 1) + ".0.0";
    }
}
