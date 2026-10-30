package net.vectolyn.cloud.backup;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.backup.manifest.BackupManifest;

/**
 * A backup archive in the backup folder.
 *
 * @param id backup id
 * @param file the archive
 * @param size size of the archive in bytes
 * @param manifest its manifest; empty if it cannot be read
 * @param problem why the manifest cannot be read; empty if it can
 * @param warnings what a fresh backup left out (only filled by {@link BackupService#create})
 */
public record BackupInfo(String id, Path file, long size, Optional<BackupManifest> manifest, Optional<String> problem,
                         List<String> warnings) {

    /** Checks and copies the values. */
    public BackupInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(problem, "problem");
        warnings = List.copyOf(warnings);
    }

    /** Whether the manifest could be read. */
    public boolean readable() {
        return manifest.isPresent();
    }
}
