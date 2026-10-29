package net.vectora.cloud.download.provider;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What to resolve. Only {@code project} is always required; which other fields a provider reads
 * is listed in its class documentation and in docs/cloud/download.md.
 *
 * @param project the project at the provider ({@code paper}, a Modrinth slug, {@code mariadb})
 * @param version the wanted version, or {@code null} for the newest one
 * @param build the wanted build, or {@code null} for the newest one
 * @param platform where the artifact runs: Modrinth loader, Geyser download key, Hangar platform
 * @param gameVersion the Minecraft version the artifact must support, or {@code null}
 * @param channel the release channel, or {@code null} for the provider's stable channel
 * @param system operating system and CPU for native artifacts (MariaDB, JREs)
 * @param options provider-specific extras, for example {@code loader} and {@code installer} for Fabric
 */
public record ArtifactRequest(
        String project,
        String version,
        String build,
        String platform,
        String gameVersion,
        String channel,
        TargetPlatform system,
        Map<String, String> options) {

    /**
     * Checks the values.
     *
     * @param project the project
     * @param version the version or {@code null}
     * @param build the build or {@code null}
     * @param platform the platform or {@code null}
     * @param gameVersion the Minecraft version or {@code null}
     * @param channel the channel or {@code null}
     * @param system the system; {@code null} means the current one
     * @param options extra options
     */
    public ArtifactRequest {
        ArtifactCoordinate.requireText(project, "project");
        system = system == null ? TargetPlatform.current() : system;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * Starts a request for the newest version of a project on the current system.
     *
     * @param project the project
     * @return the request
     */
    public static ArtifactRequest of(String project) {
        return new ArtifactRequest(project, null, null, null, null, null, null, Map.of());
    }

    /**
     * Returns a copy with a version.
     *
     * @param value the version, or {@code null} for the newest
     * @return the new request
     */
    public ArtifactRequest withVersion(String value) {
        return new ArtifactRequest(project, value, build, platform, gameVersion, channel, system, options);
    }

    /**
     * Returns a copy with a build.
     *
     * @param value the build, or {@code null} for the newest
     * @return the new request
     */
    public ArtifactRequest withBuild(String value) {
        return new ArtifactRequest(project, version, value, platform, gameVersion, channel, system, options);
    }

    /**
     * Returns a copy with a platform.
     *
     * @param value the platform
     * @return the new request
     */
    public ArtifactRequest withPlatform(String value) {
        return new ArtifactRequest(project, version, build, value, gameVersion, channel, system, options);
    }

    /**
     * Returns a copy with a Minecraft version.
     *
     * @param value the Minecraft version
     * @return the new request
     */
    public ArtifactRequest withGameVersion(String value) {
        return new ArtifactRequest(project, version, build, platform, value, channel, system, options);
    }

    /**
     * Returns a copy with a channel.
     *
     * @param value the channel
     * @return the new request
     */
    public ArtifactRequest withChannel(String value) {
        return new ArtifactRequest(project, version, build, platform, gameVersion, value, system, options);
    }

    /**
     * Returns a copy for another system.
     *
     * @param value the system
     * @return the new request
     */
    public ArtifactRequest withSystem(TargetPlatform value) {
        return new ArtifactRequest(project, version, build, platform, gameVersion, channel,
                Objects.requireNonNull(value, "system"), options);
    }

    /**
     * Returns a copy with one more option.
     *
     * @param key the option name
     * @param value the option value
     * @return the new request
     */
    public ArtifactRequest withOption(String key, String value) {
        Map<String, String> copy = new TreeMap<>(options);
        copy.put(key, value);
        return new ArtifactRequest(project, version, build, platform, gameVersion, channel, system, copy);
    }

    /**
     * Returns the version.
     *
     * @return the version, or empty for the newest
     */
    public Optional<String> versionOpt() {
        return Optional.ofNullable(version);
    }

    /**
     * Returns the build.
     *
     * @return the build, or empty for the newest
     */
    public Optional<String> buildOpt() {
        return Optional.ofNullable(build);
    }

    /**
     * Returns the platform.
     *
     * @return the platform, or empty
     */
    public Optional<String> platformOpt() {
        return Optional.ofNullable(platform);
    }

    /**
     * Returns the Minecraft version.
     *
     * @return the Minecraft version, or empty
     */
    public Optional<String> gameVersionOpt() {
        return Optional.ofNullable(gameVersion);
    }

    /**
     * Returns the channel.
     *
     * @return the channel, or empty
     */
    public Optional<String> channelOpt() {
        return Optional.ofNullable(channel);
    }

    /**
     * Returns an option.
     *
     * @param key the option name
     * @return the value, or empty
     */
    public Optional<String> option(String key) {
        return Optional.ofNullable(options.get(key));
    }
}
