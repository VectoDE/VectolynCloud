package net.vectora.cloud.api.backend;

import java.util.List;

/**
 * Backups for the API. {@link BackupServiceAccess} implements it over the {@code BackupService} of
 * cloud-backup. While {@link ApiBackend#backups()} is empty the backup endpoints answer 501
 * {@code not-available}.
 */
public interface BackupAccess {

    /**
     * Every backup in the backup folder, newest first.
     *
     * @return the backups
     * @throws ApiBackendException if the backup folder cannot be read
     */
    List<BackupView> list() throws ApiBackendException;

    /**
     * Makes a backup and waits until it is written.
     *
     * @param order what it contains
     * @return the new backup
     * @throws ApiBackendException  if it cannot be made (unknown service, backup already running, ...)
     * @throws InterruptedException if the request thread is interrupted while waiting
     */
    BackupView create(BackupOrder order) throws ApiBackendException, InterruptedException;
}
