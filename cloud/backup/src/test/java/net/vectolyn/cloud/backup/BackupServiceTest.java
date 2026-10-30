package net.vectolyn.cloud.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.vectolyn.cloud.backup.ServiceControl.State;
import net.vectolyn.cloud.backup.config.RetentionConfig;
import net.vectolyn.cloud.backup.manifest.BackupManifest;
import net.vectolyn.cloud.backup.manifest.BackupManifest.FileEntry;
import net.vectolyn.cloud.backup.manifest.ManifestCodec;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Create, list, restore, delete and prune against a temporary home with fake services. */
class BackupServiceTest {

    @TempDir
    Path home;

    private TestHome test;
    private Path lobby;
    private Path survival;
    private BackupService service;

    @BeforeEach
    void setUp() throws Exception {
        test = new TestHome(home);
        lobby = test.addServer("lobby-1", "lobby", "Lobby-Server", true);
        survival = test.addServer("survival-1", "survival", "Survival-Server", true);
        service = test.service();
    }

    // ---------------------------------------------------------------- create

    @Test
    void stoppedServicesAreCopiedWithoutExcludedFiles() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));

        assertEquals(home.resolve("backups").resolve("vectolyn-backup-" + info.id() + ".zip"), info.file());
        assertTrue(info.id().startsWith("20261029-031500-"), info.id());
        Map<String, String> entries = TestHome.entries(info.file());
        assertEquals("level-lobby-1", entries.get("services/lobby-1/world/level.dat"));
        assertEquals("region-survival-1", entries.get("services/survival-1/world/region/r.0.0.mca"));
        assertEquals("header: vectolyn\n", entries.get("services/lobby-1/plugins/TAB/config.yml"));
        assertEquals("anticheat: {}\n", entries.get("services/lobby-1/config/paper-world-defaults.yml"));
        assertTrue(entries.containsKey("manifest.json"));
        // Counter-check: nothing excluded is inside.
        for (String name : entries.keySet()) {
            assertFalse(name.endsWith(".jar") || name.endsWith(".log") || name.endsWith(".pem")
                    || name.endsWith(".secret") || name.endsWith("session.lock") || name.contains("/cache/")
                    || name.contains("/logs/") || name.endsWith(".env") || name.contains("/secrets/"), name);
        }
        // No rendered secret anywhere: not in a file, not in the manifest.
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            assertFalse(entry.getValue().contains(TestHome.SECRET), entry.getKey());
        }
        assertFalse(entries.containsKey("services/lobby-1/server.properties"));
        assertFalse(entries.containsKey("services/lobby-1/config/paper-global.yml"));
        assertFalse(entries.containsKey("services/lobby-1/plugins/LuckPerms/config.yml"));
        assertTrue(Files.exists(lobby.resolve("paper.jar")), "the source stays untouched");
        assertTrue(test.control.commands().isEmpty(), "stopped services get no commands");
    }

    @Test
    void manifestListsEveryFileWithItsHash() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));
        BackupManifest manifest = BackupService.readManifest(info.id(), info.file());

        assertEquals(info.id(), manifest.id());
        assertEquals("2026-10-29T03:15:00Z", manifest.createdAt());
        assertEquals("manual", manifest.reason());
        assertEquals("1.0.0-test", manifest.cloudVersion());
        assertEquals(2, manifest.services().size());
        assertEquals("26.3", manifest.services().getFirst().minecraftVersion());
        assertEquals("offline", manifest.services().getFirst().consistency());
        Map<String, String> entries = TestHome.entries(info.file());
        assertEquals(entries.size() - 1, manifest.files().size());
        for (FileEntry file : manifest.files()) {
            byte[] content = entries.get(file.path()).getBytes(StandardCharsets.UTF_8);
            assertEquals(content.length, file.size());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), file.sha256());
        }
    }

    @Test
    void archiveIsOwnerOnlyAndNoPartialFileStays() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(info.file()));
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(info.file().getParent()));
        try (Stream<Path> files = Files.list(info.file().getParent())) {
            assertEquals(List.of(info.file()), files.toList());
        }
    }

    @Test
    void runningServerIsSavedAndSavingIsTurnedBackOn() throws Exception {
        test.control.state("lobby-1", State.RUNNING);
        BackupInfo info = service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("lobby-1")));

        assertEquals(List.of("lobby-1: save-off", "lobby-1: save-all flush", "lobby-1: save-on"),
                test.control.commands());
        assertEquals("saved", info.manifest().orElseThrow().services().getFirst().consistency());
        assertTrue(info.warnings().isEmpty(), info.warnings().toString());
    }

    @Test
    void legacyServerMessagesAreUnderstood() throws Exception {
        test.control.state("lobby-1", State.RUNNING).server(FakeServiceControl.LEGACY);
        BackupInfo info = service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("lobby-1")));
        assertEquals("saved", info.manifest().orElseThrow().services().getFirst().consistency());
    }

    @Test
    void unconfirmedSaveLeavesTheServiceOutButStillTurnsSavingOn() throws Exception {
        test.control.state("lobby-1", State.RUNNING)
                .server((id, command) -> command.equals("save-all flush") ? null : FakeServiceControl.PAPER.answer(id, command));

        BackupInfo info = service.create(BackupRequest.all(BackupReason.SCHEDULED));

        BackupManifest manifest = info.manifest().orElseThrow();
        assertEquals("skipped", manifest.service("lobby-1").orElseThrow().consistency());
        assertTrue(manifest.files().stream().noneMatch(f -> f.path().startsWith("services/lobby-1/")));
        assertEquals("offline", manifest.service("survival-1").orElseThrow().consistency());
        assertTrue(info.warnings().getFirst().contains("save-all flush"), info.warnings().toString());
        assertEquals("lobby-1: save-on", test.control.commands().getLast());
    }

    @Test
    void interruptedSaveStillTurnsSavingBackOn() throws Exception {
        test.control.state("lobby-1", State.RUNNING);
        List<String> sent = new java.util.ArrayList<>();
        ServiceControl interrupting = new Delegating(test.control) {
            @Override
            public boolean execute(String serviceId, String command, java.util.function.Predicate<String> confirmation,
                                   Duration timeout) throws java.io.IOException, InterruptedException {
                sent.add(command);
                if (command.equals("save-all flush")) {
                    throw new InterruptedException("cancelled");
                }
                // A real wait for an answer fails at once while the thread is interrupted.
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("still interrupted");
                }
                return super.execute(serviceId, command, confirmation, timeout);
            }
        };
        BackupService cancelled = test.builder().control(interrupting).build();

        assertThrows(InterruptedException.class,
                () -> cancelled.create(BackupRequest.of(BackupReason.MANUAL, Set.of("lobby-1"))));
        assertEquals(List.of("save-off", "save-all flush", "save-on"), sent);
        assertEquals("lobby-1: save-on", test.control.commands().getLast());
        assertNoArchives();
    }

    @Test
    void stoppedServicesAreHeldStoppedWhileTheyAreCopied() throws Exception {
        List<String> holds = new java.util.ArrayList<>();
        ServiceControl recording = new Delegating(test.control) {
            @Override
            public Hold holdStopped(String serviceId) throws BackupException {
                holds.add(serviceId);
                if (serviceId.equals("survival-1")) {
                    // It was started between the state query and the hold.
                    throw new BackupException(BackupException.Reason.SERVICE_RUNNING, "started");
                }
                return super.holdStopped(serviceId);
            }
        };
        BackupService held = test.builder().control(recording).build();

        BackupInfo info = held.create(BackupRequest.all(BackupReason.SCHEDULED));
        assertEquals(List.of("lobby-1", "survival-1"), holds);
        assertEquals("offline", info.manifest().orElseThrow().service("lobby-1").orElseThrow().consistency());
        assertEquals("skipped", info.manifest().orElseThrow().service("survival-1").orElseThrow().consistency());
        assertTrue(info.warnings().getFirst().contains("survival-1 started"), info.warnings().toString());
        assertTrue(test.control.held().isEmpty(), "every hold is released");
        // A strict backup fails instead.
        assertEquals(BackupException.Reason.SERVICE_BUSY, assertThrows(BackupException.class,
                () -> held.create(BackupRequest.of(BackupReason.MANUAL, Set.of("survival-1")))).reason());

        // A restore holds its service once; the safety backup does not ask again.
        holds.clear();
        held.restore(info.id(), "lobby-1");
        assertEquals(List.of("lobby-1"), holds);
        assertTrue(test.control.held().isEmpty());
    }

    /** Passes everything to another control; tests override single methods. */
    private static class Delegating implements ServiceControl {

        private final ServiceControl inner;

        Delegating(ServiceControl inner) {
            this.inner = inner;
        }

        @Override
        public State state(String serviceId) {
            return inner.state(serviceId);
        }

        @Override
        public boolean execute(String serviceId, String command, java.util.function.Predicate<String> confirmation,
                               Duration timeout) throws java.io.IOException, InterruptedException {
            return inner.execute(serviceId, command, confirmation, timeout);
        }

        @Override
        public Hold holdStopped(String serviceId) throws BackupException {
            return inner.holdStopped(serviceId);
        }
    }

    @Test
    void namedServicesMakeTheBackupStrict() throws Exception {
        test.control.state("lobby-1", State.RUNNING).server((id, command) -> null);
        BackupException e = assertThrows(BackupException.class,
                () -> service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("lobby-1"))));
        assertEquals(BackupException.Reason.SAVE_FAILED, e.reason());
        assertEquals("lobby-1: save-on", test.control.commands().getLast());
        assertNoArchives();
    }

    @Test
    void busyServiceIsLeftOutOrFailsStrictBackups() throws Exception {
        test.control.state("survival-1", State.BUSY);
        BackupInfo info = service.create(BackupRequest.all(BackupReason.SCHEDULED));
        assertEquals("skipped", info.manifest().orElseThrow().service("survival-1").orElseThrow().consistency());

        BackupException e = assertThrows(BackupException.class,
                () -> service.createBeforeVersionChange("survival", "26.3", "26.4"));
        assertEquals(BackupException.Reason.SERVICE_BUSY, e.reason());
    }

    @Test
    void proxyIsCopiedWhileRunningWithoutSaveCommands() throws Exception {
        test.addServer("proxy-1", "proxy", "Proxy", false);
        test.control.state("proxy-1", State.RUNNING);
        BackupInfo info = service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("proxy-1")));
        assertEquals("live", info.manifest().orElseThrow().services().getFirst().consistency());
        assertTrue(test.control.commands().isEmpty());
    }

    @Test
    void missingFolderIsRecordedAsMissing() throws Exception {
        test.targets.add(new BackupTarget("lobby-2", "lobby", "26.3", home.resolve("Lobby-Server/runtime-2"), true));
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));
        assertEquals("missing", info.manifest().orElseThrow().service("lobby-2").orElseThrow().consistency());
    }

    @Test
    void unknownServiceFailsWithoutLeavingAnArchive() {
        BackupException e = assertThrows(BackupException.class,
                () -> service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("nope-1"))));
        assertEquals(BackupException.Reason.UNKNOWN_SERVICE, e.reason());
    }

    @Test
    void secondOperationDuringABackupIsRefused() throws Exception {
        test.control.state("lobby-1", State.RUNNING);
        AtomicReference<BackupException> inner = new AtomicReference<>();
        // The second operation comes from another thread while the first waits for a save command.
        test.control.onCommand(() -> {
            Thread second = new Thread(() -> {
                try {
                    service.delete("20261029-031500-0000");
                } catch (BackupException e) {
                    inner.compareAndSet(null, e);
                }
            });
            second.start();
            try {
                second.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread other = new Thread(() -> {
            try {
                service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("lobby-1")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        other.start();
        other.join();
        assertEquals(BackupException.Reason.BUSY, inner.get().reason());
    }

    @Test
    void beforeVersionChangeBacksUpTheGroupAndIsNeverPruned() throws Exception {
        BackupInfo info = service.createBeforeVersionChange("lobby", "26.3", "26.4");
        BackupManifest manifest = info.manifest().orElseThrow();
        assertEquals("pre-update", manifest.reason());
        assertEquals(List.of("lobby-1"), manifest.services().stream().map(BackupManifest.ServiceEntry::id).toList());
        assertEquals("before the Minecraft version change of lobby from 26.3 to 26.4", manifest.note());

        test.config = test.config.withRetention(new RetentionConfig(1, 0, 0, 0));
        test.clock.advance(Duration.ofMinutes(1));
        service.create(BackupRequest.all(BackupReason.MANUAL));
        test.clock.advance(Duration.ofMinutes(1));
        service.create(BackupRequest.all(BackupReason.MANUAL));
        List<String> deleted = service.prune();
        assertEquals(1, deleted.size());
        assertTrue(service.list().stream().anyMatch(b -> b.id().equals(info.id())), "pre-update backup stays");

        assertThrows(IllegalArgumentException.class, () -> service.createBeforeVersionChange("../x", "1", "2"));
        assertThrows(IllegalArgumentException.class, () -> service.createBeforeVersionChange("lobby", "26.3", "x;y"));
    }

    // ---------------------------------------------------------------- list, delete, prune

    @Test
    void listShowsNewestFirstAndBrokenArchivesWithTheirProblem() throws Exception {
        BackupInfo first = service.create(BackupRequest.all(BackupReason.MANUAL));
        test.clock.advance(Duration.ofHours(1));
        BackupInfo second = service.create(BackupRequest.all(BackupReason.MANUAL));
        Files.writeString(home.resolve("backups/vectolyn-backup-20200101-000000-abcd.zip"), "not a zip");
        Files.writeString(home.resolve("backups/unrelated.zip"), "ignored");

        List<BackupInfo> list = service.list();
        assertEquals(List.of(second.id(), first.id(), "20200101-000000-abcd"), list.stream().map(BackupInfo::id).toList());
        assertFalse(list.get(2).readable());
        assertTrue(list.get(2).problem().orElseThrow().contains("not a readable zip"));
    }

    @Test
    void deleteRemovesOnlyTheNamedBackup() throws Exception {
        BackupInfo first = service.create(BackupRequest.all(BackupReason.MANUAL));
        test.clock.advance(Duration.ofSeconds(1));
        BackupInfo second = service.create(BackupRequest.all(BackupReason.MANUAL));

        service.delete(first.id());
        assertFalse(Files.exists(first.file()));
        assertTrue(Files.exists(second.file()));

        assertEquals(BackupException.Reason.NOT_FOUND, assertThrows(BackupException.class,
                () -> service.delete(first.id())).reason());
        assertEquals(BackupException.Reason.NOT_FOUND, assertThrows(BackupException.class,
                () -> service.delete("../../cloud")).reason());
        assertEquals(BackupException.Reason.NOT_FOUND, assertThrows(BackupException.class,
                () -> service.delete("20261029-031500-ZZZZ")).reason());
    }

    @Test
    void pruneKeepsTheNewestAndOnePerDay() throws Exception {
        test.config = test.config.withRetention(new RetentionConfig(2, 3, 0, 0));
        for (int i = 0; i < 6; i++) {
            service.create(BackupRequest.all(BackupReason.SCHEDULED));
            test.clock.advance(Duration.ofHours(12));
        }
        // Six backups over three days, two per day: last 2 plus the newest of each of 3 days.
        List<String> deleted = service.prune();
        assertEquals(2, deleted.size(), deleted.toString());
        assertEquals(4, service.list().size());
        // Counter-check: a second prune changes nothing.
        assertEquals(List.of(), service.prune());
    }

    // ---------------------------------------------------------------- restore

    @Test
    void restoreBringsBackTheFilesKeepsJarsAndBacksUpTheCurrentState() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        TestHome.write(lobby.resolve("world/level.dat"), "changed");
        TestHome.write(lobby.resolve("world/new-file.dat"), "new");
        TestHome.write(lobby.resolve("logs/second.log"), "log 2");
        test.clock.advance(Duration.ofMinutes(5));

        RestoreResult result = service.restore(backup.id(), "lobby-1");

        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat")));
        assertFalse(Files.exists(lobby.resolve("world/new-file.dat")), "files newer than the backup are gone");
        assertEquals("jar", Files.readString(lobby.resolve("paper.jar")), "server jar kept");
        assertEquals("plugin jar", Files.readString(lobby.resolve("plugins/LuckPerms.jar")), "plugin jar kept");
        assertEquals("log 2", Files.readString(lobby.resolve("logs/second.log")), "logs kept");
        assertEquals("secret key", Files.readString(lobby.resolve("plugins/floodgate/key.pem")), "keys kept");
        assertEquals("lock", Files.readString(lobby.resolve("world/session.lock")));
        assertTrue(Files.readString(lobby.resolve("server.properties")).contains(TestHome.SECRET),
                "rendered secret files are kept until the next start renders them again");
        assertTrue(Files.readString(lobby.resolve("plugins/LuckPerms/config.yml")).contains(TestHome.SECRET));
        assertEquals("VECTOLYN_DB_PASSWORD=" + TestHome.SECRET + "\n",
                Files.readString(lobby.resolve("secrets/vectolyn.env")));
        assertEquals("level-survival-1", Files.readString(survival.resolve("world/level.dat")), "others untouched");
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());

        BackupInfo safety = service.find(result.safetyBackupId().orElseThrow());
        assertEquals("pre-restore", safety.manifest().orElseThrow().reason());
        assertEquals("changed", TestHome.entries(safety.file()).get("services/lobby-1/world/level.dat"));
        try (Stream<Path> siblings = Files.list(lobby.getParent())) {
            assertEquals(List.of(lobby), siblings.toList(), "no staging or old folder left");
        }
        assertTrue(test.control.held().isEmpty(), "the hold is released");
    }

    @Test
    void restoreIsRefusedWhileTheServiceRunsOrStarts() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        TestHome.write(lobby.resolve("world/level.dat"), "changed");

        test.control.state("lobby-1", State.RUNNING);
        assertEquals(BackupException.Reason.SERVICE_RUNNING, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());
        test.control.state("lobby-1", State.BUSY);
        assertEquals(BackupException.Reason.SERVICE_RUNNING, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());
        assertEquals("changed", Files.readString(lobby.resolve("world/level.dat")), "nothing was touched");

        // Counter-check: once stopped, it works.
        test.control.state("lobby-1", State.STOPPED);
        service.restore(backup.id(), "lobby-1");
        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat")));
    }

    @Test
    void restoreOfAServiceTheBackupLacksIsRefused() throws Exception {
        BackupInfo backup = service.create(BackupRequest.of(BackupReason.MANUAL, Set.of("survival-1")));
        assertEquals(BackupException.Reason.NOT_FOUND, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());
        assertEquals(BackupException.Reason.UNKNOWN_SERVICE, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "other-1")).reason());
    }

    @Test
    void changedFileContentIsDetectedByItsHash() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        Map<String, byte[]> entries = TestHome.rawEntries(backup.file());
        entries.put("services/lobby-1/world/level.dat", "evil".getBytes(StandardCharsets.UTF_8));
        TestHome.zip(backup.file(), entries);
        TestHome.write(lobby.resolve("world/level.dat"), "current");

        assertEquals(BackupException.Reason.HASH_MISMATCH, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());
        assertEquals("current", Files.readString(lobby.resolve("world/level.dat")), "service folder unchanged");
        assertNoLeftoversNextTo(lobby);
    }

    @Test
    void manipulatedManifestIsRejected() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        Map<String, byte[]> entries = TestHome.rawEntries(backup.file());
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);

        // A traversal path in the manifest.
        entries.put("manifest.json", manifest.replace("services/lobby-1/world/level.dat",
                "services/lobby-1/../../../evil.dat").getBytes(StandardCharsets.UTF_8));
        TestHome.zip(backup.file(), entries);
        BackupException traversal = assertThrows(BackupException.class, () -> service.restore(backup.id(), "lobby-1"));
        assertEquals(BackupException.Reason.CORRUPT, traversal.reason());
        assertTrue(traversal.getMessage().contains("unsafe file path"), traversal.getMessage());

        // A manifest of another backup.
        entries.put("manifest.json", manifest.replace(backup.id(), "20200101-000000-0000").getBytes(StandardCharsets.UTF_8));
        TestHome.zip(backup.file(), entries);
        assertEquals(BackupException.Reason.CORRUPT, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());

        // An unknown field.
        entries.put("manifest.json", manifest.replaceFirst("\\{", "{ \"extra\" : 1,").getBytes(StandardCharsets.UTF_8));
        TestHome.zip(backup.file(), entries);
        assertEquals(BackupException.Reason.CORRUPT, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());

        // A file dropped from the manifest while it stays in the archive.
        BackupManifest parsed = ManifestCodec.read(manifest.getBytes(StandardCharsets.UTF_8));
        List<FileEntry> fewer = parsed.files().stream()
                .filter(f -> !f.path().equals("services/lobby-1/world/level.dat")).toList();
        entries.put("manifest.json", ManifestCodec.write(new BackupManifest(parsed.schemaVersion(), parsed.id(),
                parsed.createdAt(), parsed.reason(), parsed.cloudVersion(), parsed.note(), parsed.services(),
                parsed.database(), fewer, parsed.skipped())));
        TestHome.zip(backup.file(), entries);
        BackupException extra = assertThrows(BackupException.class, () -> service.restore(backup.id(), "lobby-1"));
        assertEquals(BackupException.Reason.CORRUPT, extra.reason());
        assertTrue(extra.getMessage().contains("does not list"), extra.getMessage());

        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat")));
        assertFalse(Files.exists(home.resolve("evil.dat")));
        assertNoLeftoversNextTo(lobby);
    }

    @Test
    void fileMissingFromTheArchiveIsDetected() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        Map<String, byte[]> entries = TestHome.rawEntries(backup.file());
        entries.remove("services/survival-1/world/level.dat");
        TestHome.zip(backup.file(), entries);
        BackupException e = assertThrows(BackupException.class, () -> service.restore(backup.id(), "lobby-1"));
        assertEquals(BackupException.Reason.CORRUPT, e.reason());
        assertTrue(e.getMessage().contains("lacks"), e.getMessage());
    }

    @Test
    void traversalEntryInTheArchiveNeverLeavesTheStagingFolder() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("../../escaped.txt", "evil".getBytes(StandardCharsets.UTF_8));
        entries.put("services/lobby-1/..\\..\\escaped2.txt", "evil".getBytes(StandardCharsets.UTF_8));
        entries.putAll(TestHome.rawEntries(backup.file()));
        TestHome.zip(backup.file(), entries);

        BackupException e = assertThrows(BackupException.class, () -> service.restore(backup.id(), "lobby-1"));
        assertEquals(BackupException.Reason.CORRUPT, e.reason());
        assertFalse(Files.exists(home.resolve("escaped.txt")));
        assertFalse(Files.exists(home.getParent().resolve("escaped.txt")));
        assertFalse(Files.exists(lobby.getParent().resolve("escaped.txt")));
        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat")));
        assertNoLeftoversNextTo(lobby);
    }

    @Test
    void restoreIntoAFolderThatDoesNotExistYet() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        BackupService.deleteTree(lobby);
        RestoreResult result = service.restore(backup.id(), "lobby-1");
        assertTrue(result.safetyBackupId().isEmpty());
        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat")));
    }

    @Test
    void backupFolderInsideOrAroundAServiceFolderIsRefused() throws Exception {
        test.config = test.config.withDirectory("Lobby-Server/runtime/backups");
        assertEquals(BackupException.Reason.CONFIG, assertThrows(BackupException.class,
                () -> service.create(BackupRequest.all(BackupReason.MANUAL))).reason());
        assertFalse(Files.exists(lobby.resolve("backups")), "nothing is created inside the service");

        test.config = test.config.withDirectory(home.toString());
        assertEquals(BackupException.Reason.CONFIG, assertThrows(BackupException.class,
                () -> service.create(BackupRequest.all(BackupReason.MANUAL))).reason());

        // Counter-check: a sibling folder is fine.
        test.config = test.config.withDirectory("Lobby-Server/backups");
        assertTrue(Files.exists(service.create(BackupRequest.all(BackupReason.MANUAL)).file()));
    }

    @Test
    void recoveryMovesBackAFolderAnInterruptedRestoreLeftAside() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        // A crash between the two renames: the service folder is aside, the staging folder still there.
        Path aside = lobby.resolveSibling(BackupService.OLD_PREFIX + "runtime-1a2b3c4d");
        Files.move(lobby, aside);
        TestHome.write(lobby.resolveSibling(BackupService.STAGING_PREFIX + "123/services/lobby-1/world/level.dat"),
                "staged");
        // An earlier restore of survival-1 left preserved files aside although its folder exists.
        Path survivalAside = survival.resolveSibling(BackupService.OLD_PREFIX + "runtime-00000000");
        TestHome.write(survivalAside.resolve("paper.jar"), "old jar");
        // A crashed dump left its option file and a partial archive.
        TestHome.write(home.resolve("temp/backup/.dump-42/client.cnf"), "password=\"" + TestHome.SECRET + "\"");
        TestHome.write(home.resolve("backups/.vectolyn-backup-20261029-031500-0000.zip.partial"), "half");

        List<String> notes = service.recoverInterruptedOperations();

        assertEquals("level-lobby-1", Files.readString(lobby.resolve("world/level.dat")), "folder is back");
        assertNoLeftoversNextTo(lobby);
        assertTrue(Files.exists(survivalAside.resolve("paper.jar")), "a folder with unique files is kept");
        assertEquals(2, notes.size(), notes.toString());
        assertFalse(Files.exists(home.resolve("temp/backup/.dump-42")), "option file with the password is gone");
        assertFalse(Files.exists(home.resolve("backups/.vectolyn-backup-20261029-031500-0000.zip.partial")));
        assertTrue(Files.exists(backup.file()), "finished archives stay");
        // Counter-check: a second run finds only the kept folder again.
        assertEquals(1, service.recoverInterruptedOperations().size());
    }

    @Test
    void restoreRefusesALinkedServiceFolder() throws Exception {
        BackupInfo backup = service.create(BackupRequest.all(BackupReason.MANUAL));
        Path real = home.resolve("elsewhere");
        Files.move(lobby, real);
        try {
            Files.createSymbolicLink(lobby, real);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links need extra rights here: " + e.getMessage());
        }
        assertEquals(BackupException.Reason.IO, assertThrows(BackupException.class,
                () -> service.restore(backup.id(), "lobby-1")).reason());
        assertTrue(Files.isSymbolicLink(lobby), "the link stays");
        assertEquals("level-lobby-1", Files.readString(real.resolve("world/level.dat")));
    }

    private void assertNoArchives() throws Exception {
        Path dir = home.resolve("backups");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(List.of(), files.toList());
            }
        }
    }

    private static void assertNoLeftoversNextTo(Path folder) throws Exception {
        try (Stream<Path> siblings = Files.list(folder.getParent())) {
            assertEquals(List.of(folder), siblings.toList());
        }
    }
}
