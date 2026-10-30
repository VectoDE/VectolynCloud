package net.vectora.cloud.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.io.ConfigStore;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.GroupRules;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.node.state.ServiceRecord;
import net.vectora.cloud.node.state.ServiceStateStore;
import net.vectora.cloud.node.state.ServiceStates;
import net.vectora.cloud.node.state.StateFileException;

/**
 * Moves a cloud home of the first layout (configuration files, {@code groups/}, {@code tasks/} and
 * one {@code <Server>/template} plus {@code <Server>/runtime} folder per server at the top,
 * {@code cache/} and {@code jre/}) into the layout of {@link CloudHome}
 * ({@code docs/cloud/node.md#home-layout-migration}). Runs on every start under the home lock,
 * before the configuration is read; a home that is already migrated (marker {@value #MARKER}) or
 * new costs one file check.
 *
 * <p>Order:
 * <ol>
 *   <li>No process of the previous run may still work in the home ({@code state/services.json});
 *       otherwise the start fails and nothing is moved.</li>
 *   <li>A backup of everything that is small and edited by hand goes to
 *       {@code backups/home-layout-migration-<time>/}, owner only: the configuration files,
 *       {@code groups/}, {@code tasks/}, every {@code <Server>/template} and
 *       {@code state/services.json}. Worlds, the database files and the downloads are not copied:
 *       they are moved with a rename on the same file system, which does not touch their content.
 *       {@value #IN_PROGRESS} records the backup, so a resumed migration does not make a second one.</li>
 *   <li>The configuration files, {@code groups/} and {@code tasks/} move to {@code config/}.</li>
 *   <li>The configuration is read (without validating or writing it). {@code <Server>/template}
 *       moves to {@code local/templates/<Server>/default/}; a template that is byte for byte the
 *       built-in one of a server that is not wanted is dropped instead, the node writes it again
 *       when the server is switched on. {@code <Server>/runtime} and {@code runtime-<n>} move to
 *       {@code local/services/<group>-<n>/} when exactly one group uses the template;
 *       {@code Database/runtime} moves to {@code database/}.</li>
 *   <li>{@code cache/} and {@code jre/} move to {@code storage/}; {@code cache/libs} is dropped
 *       (the launcher unpacks its libraries to {@code storage/libs} itself).</li>
 *   <li>{@value #MARKER} is written.</li>
 * </ol>
 * Every step can be repeated: a move whose source is gone is done, a folder that exists on both
 * sides is merged, a file that exists on both sides with the same content counts as moved. An
 * interrupted start therefore just continues on the next start. Two different files at the same
 * place stop the migration with an error that names both; nothing is overwritten. A folder that
 * cannot be assigned (no or several groups use its template) is left where it is with a warning.
 * {@code secrets/}, {@code backups/}, {@code logs/}, {@code state/} and {@code temp/} keep their
 * place, so the secrets keep their owner-only permissions.
 */
final class HomeLayoutMigration {

    private static final System.Logger LOG = System.getLogger(HomeLayoutMigration.class.getName());

    /** Written once the home has the current layout. */
    static final String MARKER = "state/home-layout";

    /** Written while a migration runs, with the name of its backup folder. */
    static final String IN_PROGRESS = "state/home-layout-migration";

    /** Layout version in {@value #MARKER}. */
    static final String LAYOUT_VERSION = "2";

    /** Prefix of the backup folder below {@code backups/}. */
    static final String BACKUP_PREFIX = "home-layout-migration-";

    private static final Pattern CONFIG_FILE =
            Pattern.compile("(cloud|nodes|monitor|backup)\\.yml(\\.v[0-9]{1,4}\\.bak)?|software\\.lock\\.yml");
    private static final Pattern RUNTIME = Pattern.compile("runtime(?:-([1-9][0-9]{0,3}))?");
    private static final String PARTIAL = ".vectora-migration-partial";

    /**
     * Where the old {@code Database} folder is renamed to before its content moves to
     * {@code database/}: on Windows and macOS both names are the same folder.
     */
    static final String DATABASE_STAGING = ".vectora-migration-Database";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Top-level names of the current layout; never taken for a server folder of the old one. */
    private static final Set<String> RESERVED = Set.of(CloudHome.CONFIG_DIR, "local", CloudHome.TEMP_DIR,
            CloudHome.DATABASE_DIR, CloudHome.STORAGE_DIR, CloudHome.SECRETS_DIR, CloudHome.BACKUPS_DIR,
            CloudHome.LOGS_DIR, CloudHome.STATE_DIR, CloudHome.GROUPS_DIR, CloudHome.TASKS_DIR, "cache", "jre");

    /** What a start found and did. */
    enum Outcome {
        /** The home has the current layout already. */
        CURRENT,
        /** A new or empty home; only the marker was written. */
        NEW,
        /** An old layout was moved. */
        MIGRATED
    }

    /**
     * Result of {@link #run()}.
     *
     * @param outcome what happened
     * @param backup  the backup folder relative to the home, or empty
     * @param actions one line per move, drop or merge, relative paths
     * @param kept    folders of the old layout that were left in place, with the reason
     */
    record Result(Outcome outcome, Optional<String> backup, List<String> actions, List<String> kept) {

        Result {
            actions = List.copyOf(actions);
            kept = List.copyOf(kept);
        }
    }

    private final Path home;
    private final BuiltInTemplates templates;
    private final Liveness alive;
    private final Function<Instant, String> stamp;
    private final List<String> actions = new ArrayList<>();
    private final List<String> kept = new ArrayList<>();

    /**
     * A migration of {@code home}.
     *
     * @param home      the cloud home
     * @param templates the built-in templates, to recognise unchanged copies
     * @param alive     whether a process of {@code state/services.json} may still run; in the node
     *                  {@link #previousRunAlive}
     * @param stamp     the backup folder's time stamp
     */
    HomeLayoutMigration(Path home, BuiltInTemplates templates, Liveness alive, Function<Instant, String> stamp) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.templates = Objects.requireNonNull(templates, "templates");
        this.alive = Objects.requireNonNull(alive, "alive");
        this.stamp = Objects.requireNonNull(stamp, "stamp");
    }

    /**
     * The migration of the running node.
     *
     * @param home the cloud home
     * @return the migration
     */
    static HomeLayoutMigration of(Path home) {
        return new HomeLayoutMigration(home, BuiltInTemplates.fromClassPath(), HomeLayoutMigration::previousRunAlive,
                STAMP::format);
    }

    /**
     * Migrates the home if it has the old layout.
     *
     * @return what happened
     * @throws IOException if a process of the previous run still works in the home, two different
     *                     files meet at one place, or a move fails; the next start continues
     */
    Result run() throws IOException {
        Path marker = home.resolve(MARKER);
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && LAYOUT_VERSION.equals(Files.readString(marker, StandardCharsets.UTF_8).strip())) {
            warnAboutStrays();
            return new Result(Outcome.CURRENT, Optional.empty(), List.of(), List.of());
        }
        List<String> old = oldItems();
        if (old.isEmpty() && !Files.exists(home.resolve(IN_PROGRESS), LinkOption.NOFOLLOW_LINKS)) {
            writeMarker();
            return new Result(Outcome.NEW, Optional.empty(), List.of(), List.of());
        }
        refuseWhileThePreviousRunWorks();
        String backup = backupOnce(old);
        LOG.log(System.Logger.Level.INFO, "Home layout: moving the old layout into config/, local/, database/ and "
                + "storage/ (backup in {0}, docs/cloud/node.md#home-layout-migration)", backup);

        // Configuration first: the folders below are assigned with it.
        for (String name : rootFiles(CONFIG_FILE)) {
            move(home.resolve(name), home.resolve(CloudHome.CONFIG_DIR).resolve(name));
        }
        move(home.resolve(CloudHome.GROUPS_DIR), home.resolve(CloudHome.CONFIG_DIR).resolve(CloudHome.GROUPS_DIR));
        move(home.resolve(CloudHome.TASKS_DIR), home.resolve(CloudHome.CONFIG_DIR).resolve(CloudHome.TASKS_DIR));

        Optional<ConfigSnapshot> config = readConfig();
        for (String server : serverFolders()) {
            migrateServer(server, config);
        }
        migrateStorage();

        for (String line : kept) {
            LOG.log(System.Logger.Level.WARNING, "Home layout: left in place: {0}", line);
        }
        if (config.isEmpty()) {
            // Without the groups the worlds could not be assigned. No marker: the next start, with
            // the configuration fixed, finishes the move instead of starting empty service folders.
            LOG.log(System.Logger.Level.WARNING, "Home layout: not finished, the configuration in config/ cannot be "
                    + "read; fix it and start again, the move then continues (backup in {0})", backup);
            return new Result(Outcome.MIGRATED, Optional.of(backup), actions, kept);
        }
        writeMarker();
        Files.deleteIfExists(home.resolve(IN_PROGRESS));
        LOG.log(System.Logger.Level.INFO, "Home layout: done, {0} change(s); backup in {1}", actions.size(), backup);
        return new Result(Outcome.MIGRATED, Optional.of(backup), actions, kept);
    }

    /**
     * Whether a pid recorded in {@code state/services.json} still belongs to the process of the
     * previous run: alive and started at the recorded time (within a second), or alive with a
     * start time that cannot be read.
     *
     * @param pid       the pid
     * @param startedAt the recorded start, epoch millis
     * @return whether it may still run
     */
    static boolean previousRunAlive(long pid, long startedAt) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        Optional<Instant> started = handle.get().info().startInstant();
        return started.isEmpty() || Math.abs(started.get().toEpochMilli() - startedAt) <= 1000;
    }

    // ------------------------------------------------------------------ detection

    /** What of the old layout is there, relative names. */
    private List<String> oldItems() throws IOException {
        List<String> items = new ArrayList<>(rootFiles(CONFIG_FILE));
        for (String folder : List.of(CloudHome.GROUPS_DIR, CloudHome.TASKS_DIR, "cache", "jre")) {
            if (Files.isDirectory(home.resolve(folder), LinkOption.NOFOLLOW_LINKS)) {
                items.add(folder + "/");
            }
        }
        for (String server : serverFolders()) {
            items.add(server + "/");
        }
        return items;
    }

    /** Files directly in the home whose name matches. */
    private List<String> rootFiles(Pattern pattern) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(home)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (pattern.matcher(name).matches() && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    names.add(name);
                }
            }
        }
        names.sort(null);
        return names;
    }

    /** Folders at the top that hold a {@code template} or {@code runtime*} folder of the old layout. */
    private List<String> serverFolders() throws IOException {
        List<String> servers = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(home)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (RESERVED.contains(name) || name.startsWith(".")
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        || !name.matches(net.vectora.cloud.config.validation.ConfigNames.TEMPLATE)) {
                    continue;
                }
                if (Files.isDirectory(child.resolve("template"), LinkOption.NOFOLLOW_LINKS) || !runtimes(child).isEmpty()) {
                    servers.add(name);
                }
            }
        }
        String database = ServerKind.DATABASE.templateFolder();
        if (!servers.contains(database) && Files.isDirectory(home.resolve(DATABASE_STAGING), LinkOption.NOFOLLOW_LINKS)) {
            servers.add(database); // an interrupted run had renamed it already
        }
        servers.sort(null);
        return servers;
    }

    /** {@code runtime} (index 1) and {@code runtime-<n>} below a server folder, by index. */
    private static Map<Integer, Path> runtimes(Path server) throws IOException {
        Map<Integer, Path> result = new TreeMap<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(server)) {
            for (Path child : children) {
                Matcher m = RUNTIME.matcher(child.getFileName().toString());
                if (m.matches() && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(m.group(1) == null ? 1 : Integer.parseInt(m.group(1)), child);
                }
            }
        }
        return result;
    }

    /** An old file next to the new layout is not touched, only reported. */
    private void warnAboutStrays() throws IOException {
        List<String> strays = oldItems();
        if (!strays.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Home layout: {0} belong(s) to the old layout and are not read; the "
                    + "configuration is in config/ (docs/cloud/node.md#home-layout-migration)", strays);
        }
    }

    private void refuseWhileThePreviousRunWorks() throws IOException {
        ServiceStateStore store = new ServiceStateStore(home);
        Optional<ServiceStates> states;
        try {
            states = store.load();
        } catch (StateFileException e) {
            // A broken file is moved aside by the service core later; it names no process.
            return;
        }
        List<String> running = new ArrayList<>();
        for (ServiceRecord r : states.map(ServiceStates::services).orElse(List.of())) {
            if (r.pid() > 0 && r.state().hasProcess()
                    && alive.mayRun(r.pid(), r.startedAt())) {
                running.add(r.id() + " (pid " + r.pid() + ")");
            }
        }
        if (!running.isEmpty()) {
            throw new IOException("the home has the old layout, but servers of the previous run still work in it: "
                    + String.join(", ", running) + ". End them, then start VectoraCloud again; nothing was moved.");
        }
    }

    // ------------------------------------------------------------------ backup

    /** The backup of the first attempt, or a new one. */
    private String backupOnce(List<String> old) throws IOException {
        Path progress = home.resolve(IN_PROGRESS);
        if (Files.isRegularFile(progress, LinkOption.NOFOLLOW_LINKS)) {
            String recorded = Files.readString(progress, StandardCharsets.UTF_8).strip();
            if (recorded.startsWith(CloudHome.BACKUPS_DIR + "/" + BACKUP_PREFIX)
                    && Files.isDirectory(home.resolve(recorded), LinkOption.NOFOLLOW_LINKS)) {
                return recorded;
            }
        }
        String name = CloudHome.BACKUPS_DIR + "/" + BACKUP_PREFIX + stamp.apply(Instant.now());
        Path target = home.resolve(name);
        Path partial = target.resolveSibling(target.getFileName() + PARTIAL);
        deleteTree(partial);
        Files.createDirectories(partial);
        OwnerOnlyPermissions.restrictDirectory(partial);
        for (String item : old) {
            String relative = item.endsWith("/") ? item.substring(0, item.length() - 1) : item;
            Path source = home.resolve(relative);
            switch (relative) {
                case "cache", "jre" -> {
                    // Downloads: fetched again if ever needed.
                }
                default -> {
                    if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                            || relative.equals(CloudHome.GROUPS_DIR) || relative.equals(CloudHome.TASKS_DIR)) {
                        copyTree(source, partial.resolve(relative));
                    } else {
                        // A server folder: its template only; runtime folders are moved, not copied.
                        Path template = source.resolve("template");
                        if (Files.isDirectory(template, LinkOption.NOFOLLOW_LINKS)) {
                            copyTree(template, partial.resolve(relative).resolve("template"));
                        }
                    }
                }
            }
        }
        Path states = home.resolve(CloudHome.STATE_DIR).resolve(ServiceStateStore.FILE_NAME);
        if (Files.isRegularFile(states, LinkOption.NOFOLLOW_LINKS)) {
            copyTree(states, partial.resolve(CloudHome.STATE_DIR).resolve(ServiceStateStore.FILE_NAME));
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        Files.createDirectories(progress.getParent());
        Files.writeString(progress, name + "\n", StandardCharsets.UTF_8);
        return name;
    }

    // ------------------------------------------------------------------ servers

    /** Reads the moved configuration as it is; empty if it cannot be read (reported again by the start). */
    private Optional<ConfigSnapshot> readConfig() {
        try {
            return Optional.of(new ConfigStore(CloudHome.of(home)).load().snapshot());
        } catch (ConfigException e) {
            LOG.log(System.Logger.Level.WARNING, "Home layout: the configuration cannot be read, so server folders "
                    + "are moved without knowing their groups: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private void migrateServer(String server, Optional<ConfigSnapshot> config) throws IOException {
        Path folder = home.resolve(server);
        boolean database = server.equals(ServerKind.DATABASE.templateFolder());
        if (database) {
            // On a file system that ignores case, Database/runtime -> database/ would move the
            // folder into itself. The rename comes first; a resumed run finds the renamed folder.
            Path staging = home.resolve(DATABASE_STAGING);
            if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(folder, staging, StandardCopyOption.ATOMIC_MOVE);
            }
            folder = staging;
        }
        List<String> groups = config.map(c -> groupsUsing(c, server)).orElse(null);
        boolean wanted = config.map(c -> c.groups().values().stream()
                .anyMatch(g -> server.equals(g.template()) && GroupRules.isWanted(g, c.cloud()))).orElse(true);

        Path template = folder.resolve("template");
        if (Files.isDirectory(template, LinkOption.NOFOLLOW_LINKS)) {
            if ((database || !wanted) && templates.isUnchangedCopy(server, template)) {
                deleteTree(template);
                actions.add("dropped " + server + "/template (the unchanged built-in template of a server that is "
                        + "not wanted; it is written again when the server is switched on)");
            } else {
                move(template, CloudHome.of(home).templateDir(server));
            }
        }
        for (Map.Entry<Integer, Path> runtime : runtimes(folder).entrySet()) {
            String name = runtime.getValue().getFileName().toString();
            if (database && runtime.getKey() == 1) {
                move(runtime.getValue(), home.resolve(CloudHome.DATABASE_DIR));
            } else if (groups != null && groups.size() == 1 && !database) {
                String id = groups.getFirst() + "-" + runtime.getKey();
                move(runtime.getValue(), home.resolve(CloudHome.STATIC_SERVICES_DIR).resolve(id));
            } else {
                kept.add(server + "/" + name + " (" + (groups == null ? "the configuration could not be read"
                        : groups.isEmpty() ? "no group uses the template " + server
                        : "the groups " + groups + " share the template " + server) + "; move it to "
                        + CloudHome.STATIC_SERVICES_DIR + "/<group>-<n> by hand)");
            }
        }
        deleteIfEmpty(folder);
    }

    private static List<String> groupsUsing(ConfigSnapshot config, String template) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, GroupConfig> group : config.groups().entrySet()) {
            if (template.equals(group.getValue().template())) {
                names.add(group.getKey());
            }
        }
        names.sort(null);
        return names;
    }

    private void migrateStorage() throws IOException {
        Path cache = home.resolve("cache");
        if (Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) {
            Path libs = cache.resolve("libs");
            if (Files.exists(libs, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(libs);
                actions.add("dropped cache/libs (the launcher unpacks its libraries to storage/libs)");
            }
            move(cache, home.resolve(CloudHome.STORAGE_DIR));
        }
        move(home.resolve("jre"), home.resolve(CloudHome.STORAGE_DIR).resolve("jre"));
    }

    // ------------------------------------------------------------------ moving

    /**
     * Moves {@code source} to {@code target}. Done if the source is gone; a folder on both sides is
     * merged entry by entry; a file on both sides with the same content counts as moved.
     */
    private void move(Path source, Path target) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String from = relative(source);
        String to = relative(target);
        Path partial = target.resolveSibling(target.getFileName() + PARTIAL);
        deleteTree(partial);
        boolean sourceDir = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Another file system (a volume per folder): copy beside the target, then rename.
                copyTree(source, partial);
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                deleteTree(source);
            }
            actions.add("moved " + from + " to " + to);
            return;
        }
        boolean targetDir = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        if (sourceDir && targetDir) {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                stream.forEach(children::add);
            }
            children.sort(null);
            for (Path child : children) {
                move(child, target.resolve(child.getFileName().toString()));
            }
            Files.delete(source);
            return;
        }
        if (!sourceDir && !targetDir && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(source, target) == -1) {
            Files.delete(source);
            return;
        }
        throw new IOException("home layout: both " + from + " and " + to + " exist and differ. Keep the right one, "
                + "remove the other and start again; nothing was overwritten.");
    }

    private void deleteIfEmpty(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<String> left = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                left.add(child.getFileName().toString());
            }
        }
        left.remove(BuiltInTemplates.STAGING);
        if (left.isEmpty()) {
            deleteTree(folder);
        } else {
            left.sort(null);
            kept.add(relative(folder) + "/ (holds " + String.join(", ", left) + ", which the new layout has no place for)");
        }
    }

    private String relative(Path path) {
        return home.relativize(path).toString().replace('\\', '/');
    }

    private void writeMarker() throws IOException {
        Path marker = home.resolve(MARKER);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, LAYOUT_VERSION + "\n", StandardCharsets.UTF_8);
    }

    /** Copies a file or folder; links are copied as links, never followed. */
    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes a file or folder; links inside are removed as links, never followed. */
    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Whether a process recorded in {@code state/services.json} may still run. */
    @FunctionalInterface
    interface Liveness {

        /**
         * Tells whether the process may still run.
         *
         * @param pid       the recorded pid
         * @param startedAt the recorded start, epoch millis
         * @return whether it may still run
         */
        boolean mayRun(long pid, long startedAt);
    }
}
