package net.vectolyn.cloud.download.jre;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What a server group needs from Java, and the rule that turns it into one feature version.
 *
 * <ol>
 *   <li>An override from the group configuration ({@code java: 21}) wins, as long as it is not
 *       below the minimum. A lower override is refused, because the server would not start.</li>
 *   <li>Legacy groups (PandaSpigot 1.8.8) get Java {@value #LEGACY_FEATURE}. Under Java 25 Netty
 *       prints a {@code sun.misc.Unsafe} warning there.</li>
 *   <li>Every other group gets Java {@value #DEFAULT_FEATURE}.</li>
 *   <li>If the minimum is higher than that default, the minimum wins.</li>
 * </ol>
 *
 * <p>The minimum is the required {@code javaVersion.majorVersion} from Mojang's version manifest or
 * the {@code java.version.minimum} from PaperMC Fill v3; with both, the higher one. See
 * {@link #minimumOf}.
 *
 * @param minimum the lowest Java feature version the server software needs, if known
 * @param legacy {@code true} for a legacy group
 * @param override the version fixed in the group configuration, if any
 */
public record JavaRequirement(OptionalInt minimum, boolean legacy, OptionalInt override) {

    /** Java for legacy groups. */
    public static final int LEGACY_FEATURE = 21;

    /** Java for every other group. */
    public static final int DEFAULT_FEATURE = 25;

    /** Lowest feature version the cloud handles. */
    public static final int MIN_FEATURE = 8;

    /** Highest feature version the cloud handles. */
    public static final int MAX_FEATURE = 99;

    /** Server software ids ({@code software:} in a group) that run as legacy groups. */
    public static final Set<String> LEGACY_SOFTWARE = Set.of("pandaspigot");

    /**
     * Why a version was chosen.
     */
    public enum Reason {
        /** Fixed in the group configuration. */
        OVERRIDE,
        /** Legacy group. */
        LEGACY,
        /** The default for every other group. */
        DEFAULT,
        /** The server software needs more than the default. */
        MINIMUM
    }

    /**
     * The chosen version.
     *
     * @param feature the Java feature version
     * @param reason why it was chosen
     */
    public record Choice(int feature, Reason reason) {

        /**
         * Checks the values.
         *
         * @param feature the feature version
         * @param reason the reason
         */
        public Choice {
            checkRange(feature, "feature");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "Java " + feature + " (" + reason.name().toLowerCase(Locale.ROOT) + ")";
        }
    }

    /**
     * Checks the values.
     *
     * @param minimum the minimum or empty
     * @param legacy whether the group is a legacy group
     * @param override the override or empty
     */
    public JavaRequirement {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(override, "override");
        if (minimum.isPresent() && (minimum.getAsInt() < 1 || minimum.getAsInt() > MAX_FEATURE)) {
            throw new IllegalArgumentException("minimum must be between 1 and " + MAX_FEATURE);
        }
        if (override.isPresent()) {
            checkRange(override.getAsInt(), "override");
        }
    }

    /**
     * Builds the requirement of a group.
     *
     * @param software the group's {@code software}, for example {@code paper} or {@code pandaspigot}
     * @param minimum the minimum from Mojang or Fill, if known
     * @param configured the group's {@code java} setting: 0 for {@code auto}, otherwise a major version
     * @return the requirement
     */
    public static JavaRequirement forGroup(String software, OptionalInt minimum, int configured) {
        Objects.requireNonNull(software, "software");
        return new JavaRequirement(minimum, LEGACY_SOFTWARE.contains(software.toLowerCase(Locale.ROOT)),
                configured == 0 ? OptionalInt.empty() : OptionalInt.of(configured));
    }

    /**
     * Combines minimums from several sources, for example Mojang's {@code javaVersion} and the Fill
     * minimum: the highest one counts.
     *
     * @param minimums the known minimums
     * @return the highest, or empty if none is known
     */
    public static OptionalInt minimumOf(OptionalInt... minimums) {
        return java.util.Arrays.stream(minimums).filter(OptionalInt::isPresent).mapToInt(OptionalInt::getAsInt).max();
    }

    /**
     * Chooses the Java feature version.
     *
     * @return the choice
     * @throws JreException if the override is lower than the minimum
     */
    public Choice choose() throws JreException {
        int min = minimum.orElse(0);
        if (override.isPresent()) {
            int fixed = override.getAsInt();
            if (fixed < min) {
                throw new JreException("The group is set to Java " + fixed + ", but its server software needs Java "
                        + min + " or newer. Set java: auto or java: " + Math.max(min, MIN_FEATURE)
                        + " (or higher) in the group configuration.");
            }
            return new Choice(fixed, Reason.OVERRIDE);
        }
        int base = legacy ? LEGACY_FEATURE : DEFAULT_FEATURE;
        if (min > base) {
            return new Choice(min, Reason.MINIMUM);
        }
        return new Choice(base, legacy ? Reason.LEGACY : Reason.DEFAULT);
    }

    private static void checkRange(int feature, String name) {
        if (feature < MIN_FEATURE || feature > MAX_FEATURE) {
            throw new IllegalArgumentException(name + " must be a Java feature version from " + MIN_FEATURE + " to "
                    + MAX_FEATURE + ": " + feature);
        }
    }
}
