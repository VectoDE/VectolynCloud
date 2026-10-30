package net.vectolyn.cloud.api.backend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.vectolyn.cloud.backup.BackupException;
import net.vectolyn.cloud.backup.BackupInfo;
import net.vectolyn.cloud.backup.BackupReason;
import net.vectolyn.cloud.backup.BackupRequest;
import net.vectolyn.cloud.backup.BackupService;
import net.vectolyn.cloud.backup.BackupTarget;
import net.vectolyn.cloud.backup.manifest.BackupManifest;

/**
 * {@link BackupAccess} over the {@link BackupService} of cloud-backup. cloud-node passes
 * {@code Optional.of(new BackupServiceAccess(backups))} from {@link ApiBackend#backups()}.
 *
 * <p>Backups made through the API have the reason {@link BackupReason#MANUAL}, like
 * {@code backup create} at the console, so retention treats them the same. The view holds no
 * path, only the id.
 */
public final class BackupServiceAccess implements BackupAccess {

    private static final System.Logger LOG = System.getLogger(BackupServiceAccess.class.getName());

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

    /**
     * Maps the reasons of cloud-backup to HTTP. Only the messages of the client's own mistakes and of
     * conflicts (ids, service names) are passed on. The other reasons carry absolute paths, file
     * system errors and database tool output; the client gets the reason, the node log the detail.
     */
    static ApiBackendException translate(BackupException e) {
        ApiBackendException.Reason reason = switch (e.reason()) {
            case NOT_FOUND -> ApiBackendException.Reason.NOT_FOUND;
            case UNKNOWN_SERVICE -> ApiBackendException.Reason.INVALID;
            case BUSY, SERVICE_RUNNING, SERVICE_BUSY -> ApiBackendException.Reason.CONFLICT;
            default -> ApiBackendException.Reason.UNAVAILABLE;
        };
        if (reason != ApiBackendException.Reason.UNAVAILABLE) {
            return new ApiBackendException(reason, e.getMessage() == null ? "The backup failed." : e.getMessage());
        }
        String code = e.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
        LOG.log(System.Logger.Level.WARNING, "Backup through the API failed ({0}): {1}", code, e.getMessage());
        return new ApiBackendException(reason, "The backup failed (" + code + "). The node log has the details.");
    }
}
