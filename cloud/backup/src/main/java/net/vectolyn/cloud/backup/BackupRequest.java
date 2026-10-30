package net.vectolyn.cloud.backup;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a backup should contain.
 *
 * @param reason why it is made
 * @param allServices whether every static service is included; otherwise only {@link #services()}
 * @param services the service ids when {@code allServices} is false (may be empty: database only)
 * @param database whether the database is dumped (if {@code backup.yml} allows it)
 * @param note free text for the manifest, at most 300 characters; may be null
 */
public record BackupRequest(BackupReason reason, boolean allServices, Set<String> services, boolean database,
                            String note) {

    /** Checks and copies the values. */
    public BackupRequest {
        Objects.requireNonNull(reason, "reason");
        services = Set.copyOf(new TreeSet<>(services == null ? Set.of() : services));
        if (allServices && !services.isEmpty()) {
            throw new IllegalArgumentException("either all services or a list");
        }
        for (String id : services) {
            if (!BackupTarget.isServiceId(id)) {
                throw new IllegalArgumentException("service id must match " + BackupTarget.SERVICE_ID);
            }
        }
        if (note != null && (note.length() > 300 || note.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("note too long or with control characters");
        }
    }

    /** Every static service and the database. */
    public static BackupRequest all(BackupReason reason) {
        return new BackupRequest(reason, true, Set.of(), true, null);
    }

    /** Some services and the database. */
    public static BackupRequest of(BackupReason reason, Set<String> services) {
        return new BackupRequest(reason, false, services, true, null);
    }

    /** A copy with the database switched on or off. */
    public BackupRequest withDatabase(boolean value) {
        return new BackupRequest(reason, allServices, services, value, note);
    }

    /** A copy with a note. */
    public BackupRequest withNote(String value) {
        return new BackupRequest(reason, allServices, services, database, value);
    }

    /**
     * Whether a part that cannot be backed up fails the whole backup instead of being left out with
     * a warning: always before a restore or a version change, and when services were named.
     */
    public boolean strict() {
        return reason == BackupReason.PRE_UPDATE || reason == BackupReason.PRE_RESTORE || !allServices;
    }
}
