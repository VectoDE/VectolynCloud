package net.vectora.cloud.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.vectora.cloud.backup.archive.ArchiveWriter;
import net.vectora.cloud.backup.archive.BackupFilter;
import net.vectora.cloud.backup.config.BackupConfig;
import net.vectora.cloud.backup.config.BackupConfigFile;
import net.vectora.cloud.backup.database.DatabaseAccess;
import net.vectora.cloud.backup.database.H2Dumper;
import net.vectora.cloud.backup.database.MariaDbDumper;
import net.vectora.cloud.backup.database.OptionFile;
import net.vectora.cloud.backup.database.ProcessToolRunner;
import net.vectora.cloud.backup.database.ToolRunner;
import net.vectora.cloud.backup.manifest.BackupManifest;
import net.vectora.cloud.backup.manifest.BackupManifest.Consistency;
import net.vectora.cloud.backup.manifest.BackupManifest.DatabaseEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.FileEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.ServiceEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.SkippedEntry;
import net.vectora.cloud.backup.manifest.ManifestCodec;
import net.vectora.cloud.backup.manifest.ManifestException;
import net.vectora.cloud.backup.manifest.ManifestPaths;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.config.validation.ConfigNames;
import net.vectora.cloud.download.archive.ArchiveException;
import net.vectora.cloud.download.archive.ExtractionLimits;
import net.vectora.cloud.download.archive.SafeExtractor;

/**
 * Creates, lists, restores and deletes backups and applies the retention rules. One operation at
 * a time; a second one fails with {@link BackupException.Reason#BUSY} instead of waiting.
 *
 * <h2>Archive</h2>
 * {@code <backups>/vectora-backup-<id>.zip}, owner-only. Inside: {@code services/<id>/...} (the
 * service folder without the paths of {@link BackupFilter}), {@code database/...} (dumps) and
 * {@code manifest.json} last. It is written as {@code .vectora-backup-<id>.zip.partial} and renamed
 * once complete.
 *
 * <h2>Consistency</h2>
 * A running server gets {@code save-off} and {@code save-all flush} through {@link ServiceControl}
 * and {@code save-on} after the copy, also when the copy fails. A stopped service is copied
 * directly. A starting or stopping service is left out (or fails a strict backup, see
 * {@link BackupRequest#strict()}).
 *
 * <h2>Restore</h2>
 * Only while the service is stopped and held stopped ({@link ServiceControl#holdStopped}). The
 * archive is unpacked with the zip-slip-safe {@link SafeExtractor} into a new folder next to the
 * service folder, every file is checked against the manifest (same set of files, size and
 * SHA-256), then the current state is backed up ({@link BackupReason#PRE_RESTORE}), and only then
 * the folders are swapped by renaming. Excluded paths of the old folder (jars, logs, caches,
 * secrets) are moved over, so the server can start again.
 */
public final class BackupService {

    /** Prefix of archive names. */
    public static final String FILE_PREFIX = "vectora-backup-";
    /** Extension of archive names. */
    public static final String FILE_SUFFIX = ".zip";
    /** Suffix of archives being written. */
    public static final String PARTIAL_SUFFIX = ".partial";

    private static final System.Logger LOG = System.getLogger(BackupService.class.getName());
    private static final Pattern ID = Pattern.compile(ManifestCodec.ID);
    private static final Pattern VERSION = Pattern.compile(ConfigNames.MINECRAFT_VERSION);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final int MAX_WALK_DEPTH = 64;
    /** Staging folder of a restore, next to the service folder. */
    static final String STAGING_PREFIX = ".vectora-restore-staging-";
    /** The previous service folder during the swap: {@code <prefix><folder name>-<random>}. */
    static final String OLD_PREFIX = ".vectora-restore-old-";
    /** Free space kept beyond the content of a restore. */
    static final long SPACE_RESERVE = 256L * 1024 * 1024;

    /** Reads {@code backup.yml}; replaced in tests. */
    @FunctionalInterface
    public interface ConfigSource {
        /** The current settings. */
        BackupConfig load() throws ConfigException;
    }

    private final Path home;
    private final BackupConfigFile configFile;
    private final ConfigSource config;
    private final BackupTargets targets;
    private final ServiceControl control;
    private final DatabaseSource database;
    private final ToolRunner tools;
    private final Clock clock;
    private final ZoneId zone;
    private final String cloudVersion;
    private final Path java;
    private final String searchPath;
    private final boolean windows;
    private final ReentrantLock lock = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();
    /** The service a restore holds stopped right now; only touched under {@link #lock}. */
    private String restoring;

    private BackupService(Builder builder) {
        this.home = builder.home;
        this.configFile = new BackupConfigFile(home);
        this.config = builder.config == null ? configFile::load : builder.config;
        this.targets = Objects.requireNonNull(builder.targets, "targets");
        this.control = Objects.requireNonNull(builder.control, "control");
        this.database = builder.database;
        this.tools = builder.tools;
        this.clock = builder.clock;
        this.zone = builder.zone;
        this.cloudVersion = builder.cloudVersion;
        this.java = builder.java;
        this.searchPath = builder.searchPath;
        this.windows = builder.windows;
    }

    /**
     * Starts a builder.
     *
     * @param home the cloud home
     * @return the builder
     */
    public static Builder builder(Path home) {
        return new Builder(home);
    }

    /** The settings, read again from {@code backup.yml}. */
    public BackupConfig settings() throws BackupException {
        try {
            return config.load();
        } catch (ConfigException e) {
            throw new BackupException(BackupException.Reason.CONFIG, e.describe(Language.EN), e);
        }
    }

    /** The backup folder of the current settings. */
    public Path directory() throws BackupException {
        return configFile.directory(settings());
    }

    /** Ids of the static services that can be backed up, for completion. */
    public List<String> serviceIds() {
        return targets.staticServices().stream().map(BackupTarget::serviceId).distinct().sorted().toList();
    }

    /** The time zone for retention and schedule. */
    public ZoneId zone() {
        return zone;
    }

    /** The clock. */
    public Clock clock() {
        return clock;
    }

    // ---------------------------------------------------------------- create

    /**
     * Makes a backup.
     *
     * @param request what to include
     * @return the new backup with the warnings of what was left out
     * @throws BackupException if it fails; no partial archive is left behind
     * @throws InterruptedException if interrupted
     */
    public BackupInfo create(BackupRequest request) throws BackupException, InterruptedException {
        acquire();
        try {
            return createLocked(request);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Makes the backup before a Minecraft version change of a group (PR 19 calls this and aborts
     * the change if it throws): every static service of the group plus the database, strict, so
     * nothing is silently left out, and never deleted by retention.
     *
     * @param group the group
     * @param fromVersion the current Minecraft version
     * @param toVersion the new Minecraft version
     * @return the backup
     * @throws BackupException if any part cannot be backed up
     * @throws InterruptedException if interrupted
     */
    public BackupInfo createBeforeVersionChange(String group, String fromVersion, String toVersion)
            throws BackupException, InterruptedException {
        if (!ConfigNames.isValidName(group)) {
            throw new IllegalArgumentException("group must match " + ConfigNames.NAME);
        }
        if (!VERSION.matcher(fromVersion).matches() || !VERSION.matcher(toVersion).matches()) {
            throw new IllegalArgumentException("versions must match " + ConfigNames.MINECRAFT_VERSION);
        }
        acquire();
        try {
            Set<String> ids = new java.util.TreeSet<>();
            for (BackupTarget target : targets.staticServices()) {
                if (target.group().equals(group)) {
                    ids.add(target.serviceId());
                }
            }
            return createLocked(BackupRequest.of(BackupReason.PRE_UPDATE, ids)
                    .withNote("before the Minecraft version change of " + group + " from " + fromVersion + " to "
                            + toVersion));
        } finally {
            lock.unlock();
        }
    }

    private BackupInfo createLocked(BackupRequest request) throws BackupException, InterruptedException {
        BackupConfig settings = settings();
        Path dir = prepareDirectory(checkOutsideServices(configFile.directory(settings)));
        Path work = prepareWorkDirectory();
        removePartials(dir);
        List<BackupTarget> selected = select(request);
        Instant now = clock.instant();
        String id = newId(now, dir);
        Path partial = dir.resolve("." + fileName(id) + PARTIAL_SUFFIX);
        Path archive = dir.resolve(fileName(id));
        Run run = new Run(id, request, settings, work);
        boolean done = false;
        try {
            Files.createFile(partial);
            OwnerOnlyPermissions.restrictFile(partial);
            try (ArchiveWriter writer = new ArchiveWriter(partial, settings.compressionLevel())) {
                Optional<DatabaseAccess> access = request.database() && settings.database().enabled()
                        ? database.access() : Optional.empty();
                run.h2 = access.isPresent() && access.get().mode() == DatabaseMode.H2;
                for (BackupTarget target : selected) {
                    run.services.add(backupService(writer, target, run));
                }
                DatabaseEntry db = access.isPresent() ? backupDatabase(writer, access.get(), run) : null;
                BackupManifest manifest = new BackupManifest(BackupManifest.SCHEMA_VERSION, id, now.toString(),
                        request.reason().id(), cloudVersion, request.note(), run.services, db, run.files, run.skipped);
                writer.finish(manifest);
                Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
                done = true;
                LOG.log(System.Logger.Level.INFO, "Backup {0} written: {1} services, {2} files, {3} warnings", id,
                        run.services.size(), run.files.size(), run.warnings.size());
                return new BackupInfo(id, archive, Files.size(archive), Optional.of(manifest), Optional.empty(),
                        run.warnings);
            }
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.IO, "backup " + id + " failed: " + e.getMessage(), e);
        } finally {
            if (!done) {
                deleteQuietly(partial);
            }
        }
    }

    /** State of one create run. */
    private static final class Run {
        final String id;
        final BackupRequest request;
        final BackupConfig settings;
        final Path work;
        final List<ServiceEntry> services = new ArrayList<>();
        final List<FileEntry> files = new ArrayList<>();
        final List<SkippedEntry> skipped = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> h2Dumped = new ArrayList<>();
        final List<String> h2Missed = new ArrayList<>();
        boolean h2;

        Run(String id, BackupRequest request, BackupConfig settings, Path work) {
            this.id = id;
            this.request = request;
            this.settings = settings;
            this.work = work;
        }
    }

    private List<BackupTarget> select(BackupRequest request) throws BackupException {
        Map<String, BackupTarget> byId = new LinkedHashMap<>();
        for (BackupTarget target : targets.staticServices()) {
            byId.putIfAbsent(target.serviceId(), target);
        }
        if (request.allServices()) {
            return List.copyOf(byId.values());
        }
        List<BackupTarget> selected = new ArrayList<>();
        for (String id : request.services()) {
            BackupTarget target = byId.get(id);
            if (target == null) {
                throw new BackupException(BackupException.Reason.UNKNOWN_SERVICE, "no static service " + id);
            }
            selected.add(target);
        }
        selected.sort(Comparator.comparing(BackupTarget::serviceId));
        return selected;
    }

    private ServiceEntry backupService(ArchiveWriter writer, BackupTarget target, Run run)
            throws BackupException, IOException, InterruptedException {
        String id = target.serviceId();
        if (!Files.isDirectory(target.directory(), LinkOption.NOFOLLOW_LINKS)) {
            return entry(target, Consistency.MISSING);
        }
        ServiceControl.State state = control.state(id);
        if (state == ServiceControl.State.BUSY) {
            return leaveOut(target, run, BackupException.Reason.SERVICE_BUSY, id + " is starting or stopping");
        }
        if (state == ServiceControl.State.STOPPED) {
            ServiceControl.Hold hold;
            try {
                // The service must not start while its files are copied. A restore holds it already.
                hold = id.equals(restoring) ? () -> { } : control.holdStopped(id);
            } catch (BackupException e) {
                return leaveOut(target, run, BackupException.Reason.SERVICE_BUSY, id + " started during the backup");
            }
            try (hold) {
                copyService(writer, target, run);
                if (run.h2) {
                    dumpH2(writer, target, run);
                }
            }
            return entry(target, Consistency.OFFLINE);
        }
        if (!target.worldSaving()) {
            copyService(writer, target, run);
            if (run.h2) {
                run.h2Missed.add(id);
            }
            return entry(target, Consistency.LIVE);
        }
        Duration timeout = run.settings.saveTimeout().duration();
        String failure = null;
        // save-on follows in every case once save-off may have reached the server, also when
        // waiting for a confirmation is interrupted.
        try {
            try {
                if (!control.execute(id, SaveCommands.SAVE_OFF, SaveCommands.SAVE_OFF_DONE, timeout)) {
                    failure = id + " did not confirm save-off within " + timeout.toSeconds() + " s";
                } else if (!control.execute(id, SaveCommands.SAVE_ALL_FLUSH, SaveCommands.SAVE_ALL_DONE, timeout)) {
                    failure = id + " did not confirm save-all flush within " + timeout.toSeconds() + " s";
                }
            } catch (IOException e) {
                failure = id + " did not take the save commands: " + e.getMessage();
            }
            if (failure != null) {
                return leaveOut(target, run, BackupException.Reason.SAVE_FAILED, failure);
            }
            copyService(writer, target, run);
            if (run.h2) {
                run.h2Missed.add(id);
            }
            return entry(target, Consistency.SAVED);
        } finally {
            saveOn(target, run, timeout);
        }
    }

    /**
     * Sends {@code save-on}. A pending interrupt is put aside for this one command, so the server
     * never stays without automatic saving because a backup was cancelled; it is restored after.
     */
    private void saveOn(BackupTarget target, Run run, Duration timeout) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!control.execute(target.serviceId(), SaveCommands.SAVE_ON, SaveCommands.SAVE_ON_DONE, timeout)) {
                run.warnings.add(target.serviceId() + " did not confirm save-on; check it with the console");
            }
        } catch (IOException e) {
            run.warnings.add(target.serviceId() + " did not take save-on: " + e.getMessage());
        } catch (InterruptedException e) {
            interrupted = true;
            run.warnings.add("the wait for save-on of " + target.serviceId() + " was interrupted; check it with the "
                    + "console");
            LOG.log(System.Logger.Level.WARNING, "Interrupted while {0} turned saving back on; check it",
                    target.serviceId());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ServiceEntry entry(BackupTarget target, Consistency consistency) {
        return new ServiceEntry(target.serviceId(), target.group(), target.minecraftVersion(), consistency.id());
    }

    private static ServiceEntry leaveOut(BackupTarget target, Run run, BackupException.Reason reason, String message)
            throws BackupException {
        if (run.request.strict()) {
            throw new BackupException(reason, message);
        }
        run.warnings.add(message + "; left out of backup " + run.id);
        return entry(target, Consistency.SKIPPED);
    }

    private void copyService(ArchiveWriter writer, BackupTarget target, Run run) throws IOException {
        Path root = target.directory();
        String prefix = ManifestPaths.servicePrefix(target.serviceId());
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_WALK_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink() || attrs.isOther() || BackupFilter.excludesFolder(root.relativize(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = root.relativize(file);
                if (BackupFilter.excludesFile(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                String entry = prefix + slashed(relative);
                if (!ManifestPaths.isSafe(entry)) {
                    run.skipped.add(new SkippedEntry(printable(entry), "name is not portable"));
                    return FileVisitResult.CONTINUE;
                }
                try {
                    run.files.add(writer.add(entry, file));
                } catch (ArchiveWriter.UnreadableFileException e) {
                    run.skipped.add(new SkippedEntry(entry, "unreadable"));
                    run.warnings.add(entry + " could not be read and was left out");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                String entry = prefix + slashed(root.relativize(file));
                run.skipped.add(new SkippedEntry(printable(entry), "unreadable"));
                run.warnings.add(printable(entry) + " could not be read and was left out");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void dumpH2(ArchiveWriter writer, BackupTarget target, Run run)
            throws BackupException, IOException, InterruptedException {
        Optional<H2Dumper.H2Database> h2 = H2Dumper.find(target.directory());
        if (h2.isEmpty()) {
            return;
        }
        Path script = run.work.resolve(run.id + "-" + target.serviceId() + "-h2.sql");
        try {
            new H2Dumper(tools, java, run.settings.database().timeout().duration()).dump(h2.get(), script, run.work);
            run.files.add(writer.add(ManifestPaths.DATABASE + target.serviceId() + "-luckperms-h2.sql", script));
            run.h2Dumped.add(target.serviceId());
        } catch (BackupException e) {
            if (run.request.strict()) {
                throw e;
            }
            run.h2Missed.add(target.serviceId());
            run.warnings.add("H2 script of " + target.serviceId() + " failed: " + e.getMessage()
                    + "; its database file is in the archive");
        } finally {
            deleteQuietly(script);
        }
    }

    private DatabaseEntry backupDatabase(ArchiveWriter writer, DatabaseAccess access, Run run)
            throws BackupException, IOException, InterruptedException {
        String mode = access.mode().name().toLowerCase(Locale.ROOT);
        switch (access.mode()) {
            case DOCKER -> {
                run.warnings.add("database mode docker: the cloud does not dump the container. " + dockerHint(access));
                return new DatabaseEntry(mode, "manual", null, "dump the container by hand, see docs/cloud/backup.md");
            }
            case H2 -> {
                String detail = "SCRIPT TO for: " + (run.h2Dumped.isEmpty() ? "none" : String.join(", ", run.h2Dumped));
                if (!run.h2Missed.isEmpty()) {
                    detail += "; files only (running or failed): " + String.join(", ", run.h2Missed);
                    run.warnings.add("H2 databases are dumped only while their service is stopped; raw files only for "
                            + String.join(", ", run.h2Missed));
                }
                return new DatabaseEntry(mode, "per-service", null, shorten(detail));
            }
            default -> {
                return dumpServer(writer, access, run, mode);
            }
        }
    }

    private DatabaseEntry dumpServer(ArchiveWriter writer, DatabaseAccess access, Run run, String mode)
            throws BackupException, IOException, InterruptedException {
        String configured = run.settings.database().dumpExecutable();
        Optional<Path> executable = MariaDbDumper.findExecutable(access, configured == null ? null : Path.of(configured),
                searchPath, windows);
        try {
            if (executable.isEmpty()) {
                throw new BackupException(BackupException.Reason.DATABASE, configured != null
                        ? "database.dump-executable in backup.yml does not exist"
                        : "no mariadb-dump or mysqldump found" + (access.mode() == DatabaseMode.MANAGED
                        ? " in the managed server" : " on PATH; set database.dump-executable in backup.yml"));
            }
            Path dump = run.work.resolve(run.id + "-" + access.database() + ".sql");
            try {
                new MariaDbDumper(tools, run.settings.database().timeout().duration())
                        .dump(access, executable.get(), dump, run.work);
                String entry = ManifestPaths.DATABASE + access.database() + ".sql";
                run.files.add(writer.add(entry, dump));
                return new DatabaseEntry(mode, "dumped", entry, executable.get().getFileName().toString());
            } finally {
                deleteQuietly(dump);
            }
        } catch (BackupException e) {
            if (run.request.strict()) {
                throw e;
            }
            run.warnings.add("database dump failed: " + e.getMessage());
            return new DatabaseEntry(mode, "failed", null, shorten(e.getMessage()));
        }
    }

    /** How to dump the Docker database by hand, without any password on the command line. */
    static String dockerHint(DatabaseAccess access) {
        return "In Server-Network/Database/runtime run: docker compose --env-file ../../secrets/vectora.env "
                + "-f compose.database.yml exec -T vectora-database sh -c 'MYSQL_PWD=\"$MARIADB_ROOT_PASSWORD\" "
                + "exec mariadb-dump -uroot --single-transaction --routines --events --triggers --databases "
                + access.database() + "' > " + access.database() + ".sql";
    }

    // ---------------------------------------------------------------- list, delete, prune

    /**
     * Every archive in the backup folder, newest first. Archives whose manifest cannot be read are
     * listed with the problem.
     *
     * @return the backups
     * @throws BackupException if the folder cannot be read
     */
    public List<BackupInfo> list() throws BackupException {
        Path dir = directory();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<BackupInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
                if (!ID.matcher(id).matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                result.add(inspect(id, file));
            }
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.IO, "cannot list " + dir + ": " + e.getMessage(), e);
        }
        result.sort(Comparator.comparing(BackupInfo::id).reversed());
        return result;
    }

    /**
     * One backup.
     *
     * @param id the backup id
     * @return the backup, possibly with a problem
     * @throws BackupException with {@link BackupException.Reason#NOT_FOUND} if there is none
     */
    public BackupInfo find(String id) throws BackupException {
        Path file = archive(id);
        return inspect(id, file);
    }

    /**
     * Deletes a backup. The console asks first.
     *
     * @param id the backup id
     * @throws BackupException if it does not exist or cannot be deleted
     */
    public void delete(String id) throws BackupException {
        acquire();
        try {
            Path file = archive(id);
            Files.delete(file);
            LOG.log(System.Logger.Level.INFO, "Backup {0} deleted", id);
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.IO, "cannot delete backup " + id + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies the retention rules: deletes manual and scheduled backups no rule keeps. Backups
     * before a restore or a version change and archives with an unreadable manifest are never
     * deleted here.
     *
     * @return the deleted ids
     * @throws BackupException if the folder cannot be read
     */
    public List<String> prune() throws BackupException {
        acquire();
        try {
            BackupConfig settings = settings();
            removePartials(configFile.directory(settings));
            List<Retention.Candidate> candidates = new ArrayList<>();
            Map<String, Path> files = new HashMap<>();
            for (BackupInfo info : list()) {
                if (info.manifest().isPresent() && info.manifest().get().reasonType().pruned()) {
                    candidates.add(new Retention.Candidate(info.id(), info.manifest().get().created()));
                    files.put(info.id(), info.file());
                }
            }
            Set<String> keep = Retention.keep(candidates, settings.retention(), zone);
            List<String> deleted = new ArrayList<>();
            for (Retention.Candidate candidate : candidates) {
                if (!keep.contains(candidate.id())) {
                    try {
                        Files.deleteIfExists(files.get(candidate.id()));
                        deleted.add(candidate.id());
                    } catch (IOException e) {
                        LOG.log(System.Logger.Level.WARNING, "Cannot delete old backup {0}: {1}", candidate.id(),
                                e.getMessage());
                    }
                }
            }
            if (!deleted.isEmpty()) {
                LOG.log(System.Logger.Level.INFO, "Retention deleted {0} backups: {1}", deleted.size(), deleted);
            }
            return deleted;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- restore

    /**
     * Restores one service from a backup.
     *
     * @param backupId the backup
     * @param serviceId the static service
     * @return what was done
     * @throws BackupException if the service runs ({@link BackupException.Reason#SERVICE_RUNNING}),
     *     the archive is broken or manipulated ({@link BackupException.Reason#CORRUPT},
     *     {@link BackupException.Reason#HASH_MISMATCH}) or anything else fails; the service folder
     *     is then unchanged
     * @throws InterruptedException if interrupted
     */
    public RestoreResult restore(String backupId, String serviceId) throws BackupException, InterruptedException {
        acquire();
        try {
            Path file = archive(backupId);
            BackupTarget target = targets.staticServices().stream()
                    .filter(t -> t.serviceId().equals(serviceId))
                    .findFirst()
                    .orElseThrow(() -> new BackupException(BackupException.Reason.UNKNOWN_SERVICE,
                            "no static service " + serviceId));
            ServiceControl.Hold hold = control.holdStopped(serviceId);
            try {
                if (control.state(serviceId) != ServiceControl.State.STOPPED) {
                    throw new BackupException(BackupException.Reason.SERVICE_RUNNING,
                            serviceId + " must be stopped before a restore");
                }
                restoring = serviceId;
                return restoreHeld(backupId, file, target);
            } finally {
                restoring = null;
                hold.close();
            }
        } finally {
            lock.unlock();
        }
    }

    private RestoreResult restoreHeld(String backupId, Path file, BackupTarget target)
            throws BackupException, InterruptedException {
        BackupManifest manifest = readManifest(backupId, file);
        String serviceId = target.serviceId();
        ServiceEntry service = manifest.service(serviceId).orElseThrow(() -> new BackupException(
                BackupException.Reason.NOT_FOUND, "backup " + backupId + " does not contain " + serviceId));
        if (!service.hasFiles()) {
            throw new BackupException(BackupException.Reason.NOT_FOUND, "backup " + backupId + " has no files of "
                    + serviceId + " (" + service.consistency() + ")");
        }
        Path current = target.directory();
        Path parent = current.getParent();
        Path staging = null;
        try {
            if (Files.isSymbolicLink(current) || Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new BackupException(BackupException.Reason.IO, current + " is a link or not a folder; "
                        + "restore it by hand");
            }
            Files.createDirectories(parent);
            List<String> warnings = new ArrayList<>(recover(parent));
            staging = Files.createTempDirectory(parent, STAGING_PREFIX);
            OwnerOnlyPermissions.restrictDirectory(staging);
            requireSpace(staging, manifest.totalBytes());
            extractVerified(file, manifest, staging);
            Path source = staging.resolve("services").resolve(serviceId);
            Files.createDirectories(source);
            int restored = (int) manifest.files().stream().filter(f -> f.path().startsWith(service.prefix())).count();

            Optional<String> safety = Optional.empty();
            if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                BackupInfo before = createLocked(new BackupRequest(BackupReason.PRE_RESTORE, false, Set.of(serviceId),
                        false, "before restoring " + backupId));
                safety = Optional.of(before.id());
            }
            warnings.addAll(swap(current, source, parent));
            LOG.log(System.Logger.Level.INFO, "Restored {0} from backup {1} ({2} files)", serviceId, backupId, restored);
            return new RestoreResult(backupId, serviceId, restored, safety, warnings);
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.IO, "restore of " + serviceId + " failed: "
                    + e.getMessage(), e);
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    /** Unpacks with SafeExtractor and checks that exactly the manifest's files arrived, with their hashes. */
    private static void extractVerified(Path file, BackupManifest manifest, Path staging)
            throws BackupException, IOException {
        long largest = manifest.files().stream().mapToLong(FileEntry::size).max().orElse(0);
        ExtractionLimits limits = new ExtractionLimits(manifest.files().size() + 1 + 1000,
                manifest.totalBytes() + ManifestCodec.MAX_BYTES + 1, Math.max(largest, ManifestCodec.MAX_BYTES) + 1);
        try {
            new SafeExtractor(limits).extractZip(file, staging);
        } catch (ArchiveException e) {
            throw new BackupException(BackupException.Reason.CORRUPT, "archive breaks an extraction rule: "
                    + e.getMessage(), e);
        }
        Map<String, FileEntry> expected = new HashMap<>();
        for (FileEntry entry : manifest.files()) {
            expected.put(entry.path(), entry);
        }
        List<String> unexpected = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(staging)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = slashed(staging.relativize(path));
                if (name.equals(BackupManifest.ENTRY)) {
                    continue;
                }
                FileEntry entry = expected.remove(name);
                if (entry == null) {
                    unexpected.add(name);
                    continue;
                }
                if (Files.size(path) != entry.size() || !entry.sha256().equals(sha256(path))) {
                    throw new BackupException(BackupException.Reason.HASH_MISMATCH,
                            "hash of " + printable(name) + " differs from the manifest; the archive was changed or damaged");
                }
            }
        }
        if (!unexpected.isEmpty()) {
            throw new BackupException(BackupException.Reason.CORRUPT, "archive contains files the manifest does not "
                    + "list, for example " + printable(unexpected.getFirst()));
        }
        if (!expected.isEmpty()) {
            throw new BackupException(BackupException.Reason.CORRUPT, "archive lacks files the manifest lists, for "
                    + "example " + printable(expected.keySet().iterator().next()));
        }
    }

    /**
     * Swaps the folders by renaming and moves the paths a backup never contains (jars, logs,
     * caches, secrets, links, non-portable names) from the old folder into the new one.
     */
    private List<String> swap(Path current, Path source, Path parent) throws IOException {
        Path old = null;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            old = parent.resolve(OLD_PREFIX + current.getFileName() + "-" + HexFormat.of().formatHex(randomBytes(4)));
            Files.move(current, old, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(source, current, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (old != null) {
                Files.move(old, current, StandardCopyOption.ATOMIC_MOVE);
            }
            throw e;
        }
        List<String> warnings = new ArrayList<>();
        if (old != null) {
            keepPreserved(old, current, warnings);
            if (warnings.isEmpty()) {
                deleteTree(old);
                if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                    warnings.add("the previous folder could not be deleted completely: " + old);
                }
            } else {
                warnings.add("the previous folder was kept at " + old);
            }
        }
        return warnings;
    }

    private static void keepPreserved(Path old, Path current, List<String> warnings) throws IOException {
        Files.walkFileTree(old, EnumSet.noneOf(FileVisitOption.class), MAX_WALK_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(old)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = old.relativize(dir);
                if (attrs.isSymbolicLink() || attrs.isOther() || BackupFilter.excludesFolder(relative)
                        || !ManifestPaths.isSafePart(relative.getFileName().toString())) {
                    move(dir, relative);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = old.relativize(file);
                if (!attrs.isRegularFile() || BackupFilter.excludesFile(relative)
                        || !ManifestPaths.isSafe(slashed(relative))) {
                    move(file, relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                warnings.add("cannot read " + file + ": " + e.getMessage());
                return FileVisitResult.CONTINUE;
            }

            private void move(Path from, Path relative) {
                Path to = current.resolve(relative.toString());
                try {
                    if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                        return;
                    }
                    Files.createDirectories(to.getParent());
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    warnings.add("cannot keep " + relative + ": " + e.getMessage());
                }
            }
        });
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Cleans up after a crash: unfinished archives, dump files and option files of an interrupted
     * backup, and what an interrupted restore left next to the service folders. A service folder
     * that was moved aside but not yet replaced is moved back. cloud-node calls this once at
     * startup, before it starts any service, so the start does not render a fresh template into a
     * folder whose files are only aside.
     *
     * @return what needs a look from the operator, also logged; empty if nothing was found
     * @throws BackupException with {@link BackupException.Reason#BUSY} if another operation runs
     */
    public List<String> recoverInterruptedOperations() throws BackupException {
        acquire();
        try {
            List<String> notes = new ArrayList<>();
            try {
                removePartials(configFile.directory(settings()));
            } catch (BackupException e) {
                notes.add("unfinished archives not checked: " + e.getMessage());
            }
            Path work = home.resolve("temp").resolve("backup");
            if (Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    cleanWorkDirectory(work);
                } catch (IOException e) {
                    notes.add("cannot clean " + work + ": " + e.getMessage());
                }
            }
            Set<Path> parents = new java.util.TreeSet<>();
            for (BackupTarget target : targets.staticServices()) {
                parents.add(target.directory().getParent());
            }
            for (Path parent : parents) {
                notes.addAll(recover(parent));
            }
            notes.forEach(note -> LOG.log(System.Logger.Level.WARNING, "Backup recovery: {0}", note));
            return notes;
        } finally {
            lock.unlock();
        }
    }

    /** Deletes staging folders and moves back a service folder a crash left aside. */
    private static List<String> recover(Path parent) {
        List<String> notes = new ArrayList<>();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return notes;
        }
        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent, ".vectora-restore-*")) {
            children.forEach(leftovers::add);
        } catch (IOException e) {
            notes.add("cannot check " + parent + " for an interrupted restore: " + e.getMessage());
            return notes;
        }
        leftovers.sort(Comparator.naturalOrder());
        for (Path child : leftovers) {
            String name = child.getFileName().toString();
            if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (name.startsWith(STAGING_PREFIX)) {
                deleteTree(child);
                continue;
            }
            int dash = name.lastIndexOf('-');
            if (!name.startsWith(OLD_PREFIX) || dash <= OLD_PREFIX.length()) {
                continue;
            }
            Path original = parent.resolve(name.substring(OLD_PREFIX.length(), dash));
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                notes.add("an interrupted restore left " + child + " with files of the previous " + original.getFileName()
                        + " that were not moved back (jars, logs, secrets); check it and delete it by hand");
                continue;
            }
            try {
                Files.move(child, original, StandardCopyOption.ATOMIC_MOVE);
                notes.add("a restore was interrupted before the restored folder took its place; " + original
                        + " is back as it was before");
            } catch (IOException e) {
                notes.add("cannot move " + child + " back to " + original + ": " + e.getMessage());
            }
        }
        return notes;
    }

    /** Fails if the file system of {@code folder} cannot take {@code bytes} plus a reserve. */
    private static void requireSpace(Path folder, long bytes) throws BackupException, IOException {
        long usable = Files.getFileStore(folder).getUsableSpace();
        if (usable - SPACE_RESERVE < bytes) {
            throw new BackupException(BackupException.Reason.IO, "not enough free space for the restore: it unpacks "
                    + bytes + " bytes and keeps " + SPACE_RESERVE + " free, but only " + usable + " bytes are free");
        }
    }

    // ---------------------------------------------------------------- helpers

    private BackupInfo inspect(String id, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        try {
            return new BackupInfo(id, file, size, Optional.of(readManifest(id, file)), Optional.empty(), List.of());
        } catch (BackupException e) {
            return new BackupInfo(id, file, size, Optional.empty(), Optional.of(e.getMessage()), List.of());
        }
    }

    /**
     * Reads and checks the manifest of an archive, without trusting its size fields.
     *
     * @param id the id the file name claims
     * @param file the archive
     * @return the manifest
     * @throws BackupException with {@link BackupException.Reason#CORRUPT} if it is missing, broken or
     *     belongs to another id
     */
    static BackupManifest readManifest(String id, Path file) throws BackupException {
        byte[] content;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(BackupManifest.ENTRY);
            if (entry == null) {
                throw new BackupException(BackupException.Reason.CORRUPT, "backup " + id + " has no manifest");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                content = in.readNBytes(ManifestCodec.MAX_BYTES + 1);
            }
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.CORRUPT, "backup " + id + " is not a readable zip file", e);
        }
        BackupManifest manifest;
        try {
            manifest = ManifestCodec.read(content);
        } catch (ManifestException e) {
            throw new BackupException(BackupException.Reason.CORRUPT, "manifest of backup " + id + " is invalid: "
                    + e.getMessage(), e);
        }
        if (!manifest.id().equals(id)) {
            throw new BackupException(BackupException.Reason.CORRUPT, "manifest of backup " + id
                    + " belongs to another backup");
        }
        return manifest;
    }

    private Path archive(String id) throws BackupException {
        if (id == null || !ID.matcher(id).matches()) {
            throw new BackupException(BackupException.Reason.NOT_FOUND, "backup ids look like 20261029-031500-1a2b");
        }
        Path file = directory().resolve(fileName(id));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BackupException(BackupException.Reason.NOT_FOUND, "no backup " + id);
        }
        return file;
    }

    private void acquire() throws BackupException {
        if (!lock.tryLock()) {
            throw new BackupException(BackupException.Reason.BUSY, "another backup operation is running");
        }
    }

    /**
     * Refuses a backup folder inside a service folder (a backup would copy the archives, also the
     * one being written, and a restore would replace them) or one that contains a service folder.
     */
    private Path checkOutsideServices(Path dir) throws BackupException {
        for (BackupTarget target : targets.staticServices()) {
            if (dir.startsWith(target.directory()) || target.directory().startsWith(dir)) {
                throw new BackupException(BackupException.Reason.CONFIG, "directory in backup.yml must not lie inside "
                        + "or contain the folder of " + target.serviceId() + " (" + target.directory() + ")");
            }
        }
        return dir;
    }

    private Path prepareDirectory(Path dir) throws BackupException {
        try {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(dir);
                // Only a folder the cloud created is restricted; an existing folder keeps its access list.
                OwnerOnlyPermissions.restrictDirectory(dir);
            } else if (!Files.isDirectory(dir)) {
                throw new BackupException(BackupException.Reason.IO, dir + " is not a folder");
            }
            return dir;
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.IO, "cannot create " + dir + ": " + e.getMessage(), e);
        }
    }

    private Path prepareWorkDirectory() throws BackupException {
        Path work = home.resolve("temp").resolve("backup");
        try {
            Files.createDirectories(work);
            OwnerOnlyPermissions.restrictDirectory(work);
            cleanWorkDirectory(work);
            return work;
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.IO, "cannot prepare " + work + ": " + e.getMessage(), e);
        }
    }

    /** Deletes dumps and option files (with the database password) a crash left behind. */
    private static void cleanWorkDirectory(Path work) throws IOException {
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(work, "*.sql")) {
            for (Path leftover : leftovers) {
                deleteQuietly(leftover);
            }
        }
        OptionFile.removeLeftovers(work);
    }

    private static void removePartials(Path dir) {
        try (DirectoryStream<Path> partials = Files.newDirectoryStream(dir, "." + FILE_PREFIX + "*" + PARTIAL_SUFFIX)) {
            for (Path partial : partials) {
                deleteQuietly(partial);
            }
        } catch (IOException ignored) {
            // Nothing to clean or not readable; the next run tries again.
        }
    }

    private String newId(Instant now, Path dir) {
        while (true) {
            String id = ID_TIME.format(now) + "-" + HexFormat.of().formatHex(randomBytes(2));
            if (!Files.exists(dir.resolve(fileName(id)), LinkOption.NOFOLLOW_LINKS)) {
                return id;
            }
        }
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    /** {@code vectora-backup-<id>.zip}. */
    public static String fileName(String id) {
        return FILE_PREFIX + id + FILE_SUFFIX;
    }

    static String slashed(Path relative) {
        StringBuilder out = new StringBuilder();
        for (Path part : relative) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }

    private static String printable(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().limit(300).forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? '?' : c));
        return out.toString();
    }

    private static String shorten(String text) {
        String clean = printable(text);
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = ArchiveWriter.sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Cleaned up on the next run.
        }
    }

    /** Deletes a folder tree without following links. */
    static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {
            // Already gone.
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot delete {0}: {1}", root, e.getMessage());
        }
    }

    /** Builds a {@link BackupService}. */
    public static final class Builder {

        private final Path home;
        private ConfigSource config;
        private BackupTargets targets;
        private ServiceControl control;
        private DatabaseSource database = DatabaseSource.NONE;
        private ToolRunner tools = new ProcessToolRunner();
        private Clock clock = Clock.systemUTC();
        private ZoneId zone = ZoneId.systemDefault();
        private String cloudVersion = defaultVersion();
        private Path java = H2Dumper.currentJava();
        private String searchPath = System.getenv("PATH");
        private boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

        private Builder(Path home) {
            this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        }

        /** Settings source; default {@code <home>/backup.yml}. */
        public Builder config(ConfigSource value) {
            this.config = Objects.requireNonNull(value, "config");
            return this;
        }

        /** The static services (required). */
        public Builder targets(BackupTargets value) {
            this.targets = Objects.requireNonNull(value, "targets");
            return this;
        }

        /** Access to the service manager (required). */
        public Builder control(ServiceControl value) {
            this.control = Objects.requireNonNull(value, "control");
            return this;
        }

        /** The database; default none. */
        public Builder database(DatabaseSource value) {
            this.database = Objects.requireNonNull(value, "database");
            return this;
        }

        /** Runs dump tools; default {@link ProcessToolRunner}. */
        public Builder tools(ToolRunner value) {
            this.tools = Objects.requireNonNull(value, "tools");
            return this;
        }

        /** Clock and zone for ids, retention and schedule. */
        public Builder clock(Clock value, ZoneId timeZone) {
            this.clock = Objects.requireNonNull(value, "clock");
            this.zone = Objects.requireNonNull(timeZone, "timeZone");
            return this;
        }

        /** Version written into manifests; default the jar's version or {@code dev}. */
        public Builder cloudVersion(String value) {
            this.cloudVersion = Objects.requireNonNull(value, "cloudVersion");
            return this;
        }

        /** Java that runs the H2 tool; default this JVM's. */
        public Builder java(Path value) {
            this.java = Objects.requireNonNull(value, "java");
            return this;
        }

        /** {@code PATH} for finding the dump tool and whether to look for {@code .exe} files. */
        public Builder searchPath(String path, boolean windowsNames) {
            this.searchPath = path;
            this.windows = windowsNames;
            return this;
        }

        /** The service. */
        public BackupService build() {
            return new BackupService(this);
        }

        private static String defaultVersion() {
            String version = BackupService.class.getPackage().getImplementationVersion();
            return version == null || version.isBlank() ? "dev" : version;
        }
    }
}
