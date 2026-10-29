package net.vectora.cloud.backup;

import java.util.Locale;

/** Why a backup was made. Stored in the manifest. */
public enum BackupReason {
    /** {@code backup create} in the console. */
    MANUAL,
    /** The schedule in {@code backup.yml}. */
    SCHEDULED,
    /** Automatically, right before a restore overwrote the service. Retention never deletes it. */
    PRE_RESTORE,
    /** Automatically, right before a Minecraft version change (PR 19). Retention never deletes it. */
    PRE_UPDATE;

    /** Whether retention may delete backups made for this reason. */
    public boolean pruned() {
        return this == MANUAL || this == SCHEDULED;
    }

    /** Lower-case form with {@code -}, as written in the manifest: {@code pre-restore}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Parses {@link #id()}.
     *
     * @param id the text
     * @return the reason
     * @throws IllegalArgumentException if unknown
     */
    public static BackupReason ofId(String id) {
        for (BackupReason reason : values()) {
            if (reason.id().equals(id)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("unknown backup reason");
    }
}
