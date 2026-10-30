package net.vectolyn.cloud.download.cache;

import java.nio.file.Path;
import java.util.Objects;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.lock.LockedArtifact;
import net.vectolyn.cloud.download.lock.Trust;
import net.vectolyn.cloud.download.provider.ResolvedArtifact;

/**
 * A verified file in the cache.
 *
 * @param file the file under {@code cache/artifacts/}
 * @param checksum the checksum it was verified with (for TOFU: its SHA-256)
 * @param sha256 its SHA-256
 * @param trust where the checksum comes from
 * @param size its size in bytes
 * @param fromCache {@code true} if no download was needed
 */
public record DownloadedArtifact(Path file, Checksum checksum, Checksum sha256, Trust trust, long size,
        boolean fromCache) {

    /**
     * Checks the values.
     *
     * @param file the file
     * @param checksum the verified checksum
     * @param sha256 the SHA-256
     * @param trust the trust
     * @param size the size
     * @param fromCache whether it came from the cache
     */
    public DownloadedArtifact {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(trust, "trust");
    }

    /**
     * Builds the lock entry for this file.
     *
     * @param resolved what was resolved
     * @return the entry, without a previous one
     */
    public LockedArtifact toLocked(ResolvedArtifact resolved) {
        return new LockedArtifact(resolved.coordinate(), resolved.url(), resolved.fileName(), checksum, sha256, trust,
                size, null);
    }
}
