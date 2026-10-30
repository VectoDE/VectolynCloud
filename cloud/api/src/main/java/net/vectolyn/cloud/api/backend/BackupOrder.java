package net.vectolyn.cloud.api.backend;

import java.util.List;

/**
 * What {@code POST /api/v1/backups} asks for. Maps to {@code BackupRequest} of cloud-backup:
 * {@code services == null} is {@code BackupRequest.all(MANUAL)}, a list is
 * {@code BackupRequest.of(MANUAL, services)}.
 *
 * @param services service ids to back up, or null for every static service; may be empty (database only)
 * @param database whether the database is dumped (if {@code backup.yml} allows it)
 * @param note     free text for the manifest, at most 300 characters, or null
 */
public record BackupOrder(List<String> services, boolean database, String note) {

    /** Copies the list. */
    public BackupOrder {
        services = services == null ? null : List.copyOf(services);
    }
}
