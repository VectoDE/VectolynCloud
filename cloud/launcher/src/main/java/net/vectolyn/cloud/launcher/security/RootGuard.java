package net.vectolyn.cloud.launcher.security;

import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether the launcher may continue, given the {@link Elevation} and the override
 * switches. The rule from the spec: running as root or administrator is refused unless
 * {@code --allow-root} is given or {@code VECTOLYN_ALLOW_ROOT} is {@code true}; even then a
 * warning is printed.
 */
public final class RootGuard {

    /** Name of the environment variable that allows elevated starts. */
    public static final String ALLOW_ROOT_ENV = "VECTOLYN_ALLOW_ROOT";

    /** What the launcher does next. */
    public enum Decision {
        /** Not elevated: continue silently. */
        PROCEED,
        /** Elevated, but explicitly allowed: continue after a warning. */
        PROCEED_WITH_WARNING,
        /** The check failed: continue after a notice that it could not be done. */
        PROCEED_UNCHECKED,
        /** Elevated and not allowed: stop with exit code 2. */
        REFUSE
    }

    private RootGuard() {
    }

    /**
     * Makes the decision.
     *
     * @param elevation      result of {@link ElevationDetector}
     * @param allowRootFlag  {@code --allow-root} was given
     * @param allowRootEnv   value of {@code VECTOLYN_ALLOW_ROOT}, if set
     * @return the decision
     */
    public static Decision decide(Elevation elevation, boolean allowRootFlag, Optional<String> allowRootEnv) {
        return switch (elevation.state()) {
            case NOT_ELEVATED -> Decision.PROCEED;
            case UNKNOWN -> Decision.PROCEED_UNCHECKED;
            case ELEVATED -> allowRootFlag || envAllows(allowRootEnv) ? Decision.PROCEED_WITH_WARNING : Decision.REFUSE;
        };
    }

    /**
     * Reads {@code VECTOLYN_ALLOW_ROOT}. Only {@code true} (any case, surrounding blanks ignored)
     * allows an elevated start; {@code 1}, {@code yes} and everything else do not.
     *
     * @param value the variable's value, if set
     * @return {@code true} if it allows an elevated start
     */
    public static boolean envAllows(Optional<String> value) {
        return value.map(v -> v.trim().toLowerCase(Locale.ROOT)).filter("true"::equals).isPresent();
    }
}
