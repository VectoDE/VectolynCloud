package net.vectora.cloud.backup;

/**
 * Backups of the static services and the database.
 *
 * <p>Entry points: {@link BackupService} (create, list, restore, delete, backup before a Minecraft
 * version change), {@link net.vectora.cloud.backup.schedule.BackupScheduler} and
 * {@link net.vectora.cloud.backup.command.BackupCommands}. Details in docs/cloud/backup.md.
 */
public final class CloudBackupModule {

    /** Gradle project name of this module. */
    public static final String NAME = "cloud-backup";

    private CloudBackupModule() {
    }
}
