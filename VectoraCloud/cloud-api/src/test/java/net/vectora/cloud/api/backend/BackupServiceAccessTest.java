package net.vectora.cloud.api.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import net.vectora.cloud.api.http.ApiJson;
import net.vectora.cloud.backup.BackupService;
import net.vectora.cloud.backup.ServiceControl;
import org.junit.jupiter.api.io.TempDir;
import net.vectora.cloud.backup.BackupException;
import net.vectora.cloud.backup.BackupInfo;
import net.vectora.cloud.backup.manifest.BackupManifest;
import org.junit.jupiter.api.Test;

class BackupServiceAccessTest {

    private static final Path FILE = Path.of("backups", "vectora-backup-20261029-031500-ab12.zip");

    @Test
    void aReadableBackupShowsItsContentButNoPath() {
        BackupManifest manifest = new BackupManifest(1, "20261029-031500-ab12", "2026-10-29T03:15:00Z", "manual",
                "1.0.0", "before update",
                List.of(new BackupManifest.ServiceEntry("lobby-1", "lobby", "26.3", "saved")),
                new BackupManifest.DatabaseEntry("managed", "dumped", "database/vectora.sql", null),
                List.of(new BackupManifest.FileEntry("services/lobby-1/world/level.dat", 3, "0".repeat(64))), List.of());
        BackupView view = BackupServiceAccess.view(new BackupInfo("20261029-031500-ab12", FILE, 1234,
                Optional.of(manifest), Optional.empty(), List.of("left out: lobby-2")));
        assertEquals(new BackupView("20261029-031500-ab12", "2026-10-29T03:15:00Z", "manual", List.of("lobby-1"), true,
                1234, true, null, List.of("left out: lobby-2")), view);
        String json = new String(ApiJson.write(view), StandardCharsets.UTF_8);
        assertFalse(json.contains("backups") && json.contains(".zip"), "no path in the answer: " + json);
    }

    @Test
    void anUnreadableBackupIsListedWithItsProblem() {
        BackupView view = BackupServiceAccess.view(new BackupInfo("20261029-031500-ab12", FILE, 10, Optional.empty(),
                Optional.of("manifest.json is missing"), List.of()));
        assertFalse(view.readable());
        assertNull(view.createdAt());
        assertEquals("manifest.json is missing", view.problem());
    }

    @Test
    void reasonsMapToStatusCodes() {
        assertEquals(ApiBackendException.Reason.NOT_FOUND, reason(BackupException.Reason.NOT_FOUND));
        assertEquals(ApiBackendException.Reason.INVALID, reason(BackupException.Reason.UNKNOWN_SERVICE));
        assertEquals(ApiBackendException.Reason.CONFLICT, reason(BackupException.Reason.BUSY));
        assertEquals(ApiBackendException.Reason.CONFLICT, reason(BackupException.Reason.SERVICE_RUNNING));
        assertEquals(ApiBackendException.Reason.UNAVAILABLE, reason(BackupException.Reason.IO));
        assertEquals(ApiBackendException.Reason.UNAVAILABLE, reason(BackupException.Reason.DATABASE));
    }

    @Test
    void badServiceIdsAreRefusedBeforeTheServiceIsAsked() {
        // The service is never reached, so a null-free dummy is not needed: the order fails first.
        BackupServiceAccess access = new BackupServiceAccess(service(Path.of("unused-home")));
        for (List<String> services : List.of(List.of("../etc"), List.of("Lobby-1"), List.of(""))) {
            ApiBackendException e = org.junit.jupiter.api.Assertions.assertThrows(ApiBackendException.class,
                    () -> access.create(new BackupOrder(services, true, null)));
            assertEquals(ApiBackendException.Reason.INVALID, e.reason());
        }
        ApiBackendException note = org.junit.jupiter.api.Assertions.assertThrows(ApiBackendException.class,
                () -> access.create(new BackupOrder(null, true, "x".repeat(301))));
        assertTrue(note.getMessage().contains("not valid"));
    }

    private static ApiBackendException.Reason reason(BackupException.Reason reason) {
        return BackupServiceAccess.translate(new BackupException(reason, "x")).reason();
    }

    @Test
    void createAndListGoThroughTheRealService(@TempDir Path home) throws Exception {
        BackupServiceAccess access = new BackupServiceAccess(service(home));
        assertEquals(List.of(), access.list());
        BackupView created = access.create(new BackupOrder(null, false, "from the api"));
        assertTrue(created.readable());
        assertEquals("manual", created.reason());
        assertEquals(List.of(created.id()), access.list().stream().map(BackupView::id).toList());
    }

    /** A backup service without static services; nothing reaches a server. */
    private static BackupService service(Path home) {
        ServiceControl control = new ServiceControl() {
            @Override
            public State state(String serviceId) {
                return State.STOPPED;
            }

            @Override
            public boolean execute(String serviceId, String command, java.util.function.Predicate<String> confirmation,
                                   java.time.Duration timeout) {
                return false;
            }

            @Override
            public Hold holdStopped(String serviceId) {
                return () -> { };
            }
        };
        return BackupService.builder(home).targets(List::of).control(control).build();
    }
}
