package net.vectolyn.cloud.backup;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a restore did.
 *
 * @param backupId the restored backup
 * @param serviceId the service
 * @param files files written
 * @param safetyBackupId the backup of the state before the restore; empty if the folder did not exist
 * @param warnings things that need attention, for example kept leftovers
 */
public record RestoreResult(String backupId, String serviceId, int files, Optional<String> safetyBackupId,
                            List<String> warnings) {

    /** Checks and copies the values. */
    public RestoreResult {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(safetyBackupId, "safetyBackupId");
        warnings = List.copyOf(warnings);
    }
}
