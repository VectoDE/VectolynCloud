package net.vectolyn.cloud.download.lock;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.provider.ArtifactCoordinate;

/**
 * One entry of {@code software.lock.yml}: exactly which file a name stands for.
 *
 * @param coordinate provider, project, version and build
 * @param url the HTTPS URL it was downloaded from
 * @param fileName the file name the source uses; a plain base name ({@link net.vectolyn.cloud.download.FileNames})
 * @param checksum the checksum the file was verified with; for {@link Trust#TOFU} the pinned SHA-256
 * @param sha256 the SHA-256 of the file, its address in {@code cache/artifacts/}
 * @param trust where the checksum comes from
 * @param size the file size in bytes
 * @param previous the entry this one replaced, kept for a rollback; never has a previous itself
 */
public record LockedArtifact(
        ArtifactCoordinate coordinate,
        URI url,
        String fileName,
        Checksum checksum,
        Checksum sha256,
        Trust trust,
        long size,
        LockedArtifact previous) {

    /**
     * Checks the values.
     *
     * @param coordinate the coordinate
     * @param url the URL
     * @param fileName the file name
     * @param checksum the verified checksum
     * @param sha256 the SHA-256
     * @param trust the trust
     * @param size the size
     * @param previous the replaced entry or {@code null}
     */
    public LockedArtifact {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(url, "url");
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("url must be HTTPS: " + url);
        }
        net.vectolyn.cloud.download.FileNames.requireSafeBaseName(fileName, "fileName");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(trust, "trust");
        if (sha256.algorithm() != HashAlgorithm.SHA256) {
            throw new IllegalArgumentException("sha256 must be a SHA-256 checksum");
        }
        if (trust == Trust.TOFU && !checksum.matches(sha256)) {
            throw new IllegalArgumentException("a TOFU entry pins its SHA-256 as the checksum");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (previous != null && previous.previous() != null) {
            previous = previous.withoutPrevious();
        }
    }

    /**
     * Returns the entry this one replaced.
     *
     * @return the previous entry, or empty
     */
    public Optional<LockedArtifact> previousOpt() {
        return Optional.ofNullable(previous);
    }

    /**
     * Returns a copy without a previous entry.
     *
     * @return the copy
     */
    public LockedArtifact withoutPrevious() {
        return previous == null ? this : new LockedArtifact(coordinate, url, fileName, checksum, sha256, trust, size, null);
    }

    /**
     * Returns a copy with a previous entry.
     *
     * @param value the replaced entry, or {@code null}
     * @return the copy
     */
    public LockedArtifact withPrevious(LockedArtifact value) {
        return new LockedArtifact(coordinate, url, fileName, checksum, sha256, trust, size,
                value == null ? null : value.withoutPrevious());
    }

    /**
     * Tells whether two entries describe the same file, ignoring the previous entry.
     *
     * @param other the other entry
     * @return {@code true} if coordinate and SHA-256 are equal
     */
    public boolean sameFileAs(LockedArtifact other) {
        return other != null && coordinate.equals(other.coordinate) && sha256.matches(other.sha256);
    }
}
