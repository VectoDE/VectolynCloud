package net.vectolyn.cloud.download.jre;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A Java runtime the cloud can start servers with. {@link #executable()} goes straight into the
 * {@code ProcessBuilder} command line.
 *
 * @param home the Java home (the directory with {@code bin/} and {@code release})
 * @param executable {@code bin/java} or {@code bin\java.exe} below the home
 * @param feature the Java feature version, for example 21
 * @param version the full version, for example {@code 21.0.13}, or the feature version if unknown
 * @param origin where it comes from
 */
public record JavaRuntime(Path home, Path executable, int feature, String version, Origin origin) {

    /** Where a runtime comes from. */
    public enum Origin {
        /** Already installed on the machine (JAVA_HOME or PATH). */
        SYSTEM,
        /** Downloaded by the cloud into {@code <home>/jre/}. */
        MANAGED
    }

    /**
     * Checks the values.
     *
     * @param home the home
     * @param executable the executable
     * @param feature the feature version
     * @param version the version
     * @param origin the origin
     */
    public JavaRuntime {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(origin, "origin");
        if (feature < 1) {
            throw new IllegalArgumentException("feature must be positive");
        }
    }

    /**
     * Returns the name of the Java executable on a platform.
     *
     * @param windows whether the platform is Windows
     * @return {@code java.exe} on Windows, {@code java} elsewhere
     */
    public static String executableName(boolean windows) {
        return windows ? "java.exe" : "java";
    }

    @Override
    public String toString() {
        return "Java " + version + " (" + origin.name().toLowerCase(java.util.Locale.ROOT) + ", " + executable + ")";
    }
}
