package net.vectora.cloud.download.provider;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.download.hash.Checksum;

/**
 * The result of {@link ArtifactProvider#resolve}: where to download an artifact and how to check it.
 *
 * @param coordinate provider, project, version and build
 * @param url the HTTPS download URL
 * @param fileName the file name the source uses
 * @param checksum the strongest checksum the source publishes; empty means trust on first use
 * @param size the size in bytes, if the source says
 * @param javaMinimum the lowest Java major version the artifact needs, if the source says
 * @param metadata further facts from the source, for logs and the lockfile
 */
public record ResolvedArtifact(
        ArtifactCoordinate coordinate,
        URI url,
        String fileName,
        Optional<Checksum> checksum,
        OptionalLong size,
        OptionalInt javaMinimum,
        Map<String, String> metadata) {

    /**
     * Checks the values.
     *
     * @param coordinate the coordinate
     * @param url the URL, must be HTTPS
     * @param fileName the file name
     * @param checksum the checksum or empty
     * @param size the size or empty
     * @param javaMinimum the Java minimum or empty
     * @param metadata further facts
     */
    public ResolvedArtifact {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(url, "url");
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("download URL must be HTTPS: " + url);
        }
        ArtifactCoordinate.requireText(fileName, "fileName");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(javaMinimum, "javaMinimum");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Tells whether the source publishes no checksum, so the hash is pinned on the first download.
     *
     * @return {@code true} for trust on first use
     */
    public boolean trustOnFirstUse() {
        return checksum.isEmpty();
    }
}
