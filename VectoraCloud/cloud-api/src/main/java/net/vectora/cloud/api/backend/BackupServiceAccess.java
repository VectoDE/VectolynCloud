package net.vectora.cloud.api.backend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.vectora.cloud.backup.BackupException;
import net.vectora.cloud.backup.BackupInfo;
import net.vectora.cloud.backup.BackupReason;
import net.vectora.cloud.backup.BackupRequest;
import net.vectora.cloud.backup.BackupService;
import net.vectora.cloud.backup.BackupTarget;
import net.vectora.cloud.backup.manifest.BackupManifest;

/**
 * {@link BackupAccess} over the {@link BackupService} of cloud-backup. cloud-node passes
 * {@code Optional.of(new BackupServiceAccess(backups))} from {@link ApiBackend#backups()}.
 *
 * <p>Backups made through the API have the reason {@link BackupReason#MANUAL}, like
 * {@code backup create} at the console, so retention treats them the same. The view holds no
 * path, only the id.
 */
public final class BackupServiceAccess implements BackupAccess {

    private final BackupService backups;

    /**
     * Creates the adapter.
     *
     * @param backups the node's backup service
     */
    public BackupServiceAccess(BackupService backups) {
        this.backups = Objects.requireNonNull(backups, "backups");
    }

    @Override
    public List<BackupView> list() throws ApiBackendException {
        try {
            List<BackupView> views = new ArrayList<>();
            for (BackupInfo info : backups.list()) {
                views.add(view(info));
            }
            return views;
        } catch (BackupException e) {
            throw translate(e);
        }
    }

    @Override
    public BackupView create(BackupOrder order) throws ApiBackendException, InterruptedException {
        BackupRequest request;
        try {
            if (order.services() == null) {
                request = BackupRequest.all(BackupReason.MANUAL);
            } else {
                for (String id : order.services()) {
                    if (!BackupTarget.isServiceId(id)) {
                        throw new ApiBackendException(ApiBackendException.Reason.INVALID, "Not a service id.");
                    }
                }
                request = BackupRequest.of(BackupReason.MANUAL, new LinkedHashSet<>(order.services()));
            }
            request = request.withDatabase(order.database()).withNote(order.note());
        } catch (IllegalArgumentException e) {
            throw new ApiBackendException(ApiBackendException.Reason.INVALID, "The backup request is not valid.");
        }
        try {
            return view(backups.create(request));
        } catch (BackupException e) {
            throw translate(e);
        }
    }

    /** Maps a backup to the API view. */
    static BackupView view(BackupInfo info) {
        if (info.manifest().isEmpty()) {
            return new BackupView(info.id(), null, null, List.of(), false, info.size(), false,
                    info.problem().orElse(null), info.warnings());
        }
        BackupManifest manifest = info.manifest().get();
        List<String> services = manifest.services().stream().map(BackupManifest.ServiceEntry::id).toList();
        return new BackupView(info.id(), manifest.createdAt(), manifest.reason(), services,
                manifest.database() != null, info.size(), true, null, info.warnings());
    }

    /** Maps the reasons of cloud-backup to HTTP; the message names only files, services and ids. */
    static ApiBackendException translate(BackupException e) {
        ApiBackendException.Reason reason = switch (e.reason()) {
            case NOT_FOUND -> ApiBackendException.Reason.NOT_FOUND;
            case UNKNOWN_SERVICE -> ApiBackendException.Reason.INVALID;
            case BUSY, SERVICE_RUNNING, SERVICE_BUSY -> ApiBackendException.Reason.CONFLICT;
            default -> ApiBackendException.Reason.UNAVAILABLE;
        };
        return new ApiBackendException(reason, e.getMessage() == null ? "The backup failed." : e.getMessage());
    }
}
