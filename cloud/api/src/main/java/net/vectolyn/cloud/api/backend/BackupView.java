package net.vectolyn.cloud.api.backend;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Objects;

/**
 * One backup as the API shows it, built by {@link BackupServiceAccess} from {@code BackupInfo} of
 * cloud-backup. It holds no path, only the id under which {@code backup restore} finds the archive.
 *
 * @param id        backup id
 * @param createdAt when it was made (ISO-8601), or null if the manifest cannot be read
 * @param reason    why it was made, for example {@code manual}, {@code scheduled}, {@code pre-update}
 * @param services  the service ids it contains
 * @param database  whether it contains a database dump
 * @param sizeBytes size of the archive
 * @param readable  whether its manifest can be read
 * @param problem   why the manifest cannot be read, or null
 * @param warnings  what a fresh backup left out
 */
@JsonPropertyOrder({"id", "created-at", "reason", "services", "database", "size-bytes", "readable", "problem",
        "warnings"})
public record BackupView(String id, String createdAt, String reason, List<String> services, boolean database,
                         long sizeBytes, boolean readable, String problem, List<String> warnings) {

    /** Copies the lists. */
    public BackupView {
        Objects.requireNonNull(id, "id");
        services = services == null ? List.of() : List.copyOf(services);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
