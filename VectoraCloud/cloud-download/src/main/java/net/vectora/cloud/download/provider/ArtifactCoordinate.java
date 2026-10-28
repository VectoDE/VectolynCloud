package net.vectora.cloud.download.provider;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies one concrete artifact: which source, which project, which version and build.
 *
 * @param provider the provider id, for example {@code papermc}
 * @param project the project at that provider, for example {@code paper}
 * @param version the resolved version, for example {@code 26.3}
 * @param build the resolved build, or {@code null} if the source has no builds
 */
public record ArtifactCoordinate(String provider, String project, String version, String build) {

    /**
     * Checks the values.
     *
     * @param provider the provider id
     * @param project the project
     * @param version the version
     * @param build the build, may be {@code null}
     */
    public ArtifactCoordinate {
        requireText(provider, "provider");
        requireText(project, "project");
        requireText(version, "version");
        if (build != null) {
            requireText(build, "build");
        }
    }

    /**
     * Returns the build.
     *
     * @return the build, or empty
     */
    public Optional<String> buildOpt() {
        return Optional.ofNullable(build);
    }

    @Override
    public String toString() {
        return provider + ":" + project + ":" + version + (build == null ? "" : "#" + build);
    }

    static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be 1 to 256 printable characters");
        }
    }
}
