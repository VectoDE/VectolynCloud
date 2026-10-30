package net.vectolyn.cloud.config;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.ConfigStore;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.operation.ConfigOperation;
import net.vectolyn.cloud.config.validation.ConfigValidator;
import net.vectolyn.cloud.config.validation.JvmFlagPolicy;

/**
 * The one configuration layer that console, setup assistant, files and later the REST API and
 * the dashboard share.
 *
 * <p>Every change goes through {@link #apply}: the operations compute a new snapshot, the
 * {@link ConfigValidator} checks it as a whole, the changed files are replaced atomically, and the
 * listeners get a {@link ConfigChanged}. If validation fails nothing is written and the current
 * snapshot stays. Files edited by hand are picked up by {@link #startWatching()} (or
 * {@link #reload()}); an invalid file keeps the previous snapshot active and is reported through
 * {@link ConfigListener#reloadFailed}.
 *
 * <p>Thread-safe. Reads ({@link #snapshot()}) never block; changes and reloads are serialized.
 *
 * <p>Events are delivered in the order of the changes, to every listener. A listener that calls
 * {@link #apply} itself gets the result at once, but the event of that nested change is queued and
 * delivered only after every listener has seen the change that triggered it.
 */
public final class ConfigService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ConfigService.class.getName());
    private static final Duration QUIET_PERIOD = Duration.ofMillis(200);

    private final ConfigStore store;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<ConfigListener> listeners = new CopyOnWriteArrayList<>();
    /** Events waiting for delivery; only touched while {@link #lock} is held. */
    private final ArrayDeque<Runnable> pendingEvents = new ArrayDeque<>();
    private boolean dispatching;
    private volatile ConfigSnapshot current;
    /** Mirror mode of a cluster member, see {@link #mirrorMode(boolean)}. */
    private volatile boolean mirrored;
    private ConfigWatcher watcher;
    private boolean closed;

    private ConfigService(ConfigStore store, ConfigSnapshot initial) {
        this.store = store;
        this.current = initial;
    }

    /** Opens the configuration in {@code home}; see {@link #open(ConfigStore)}. */
    public static ConfigService open(CloudHome home) throws ConfigException, IOException {
        return open(new ConfigStore(home));
    }

    /**
     * Loads and validates the configuration. Creates {@code groups/} and {@code tasks/} if they are
     * missing, writes a default {@code cloud.yml} and {@code nodes.yml} if those are missing, and
     * rewrites files with an older {@code schema-version} in the current format (the old file is kept
     * as {@code <file>.v<version>.bak}).
     *
     * @throws ConfigException if a file is invalid; nothing is written then
     */
    public static ConfigService open(ConfigStore store) throws ConfigException, IOException {
        CloudHome home = store.home();
        Files.createDirectories(home.groupsDir());
        Files.createDirectories(home.tasksDir());
        ConfigStore.Loaded loaded = store.load();
        requireValid(loaded.snapshot());
        persistLoaded(store, loaded, loaded.missingFiles());
        logWarnings(loaded.snapshot());
        return new ConfigService(store, loaded.snapshot());
    }

    /** The current configuration. Never null, never blocks. */
    public ConfigSnapshot snapshot() {
        return current;
    }

    /**
     * Problems that do not stop the configuration from loading, for example a JVM flag in a
     * group file that is not on the allowlist ({@link JvmFlagPolicy#warnings}). The REST API cannot
     * add such a flag (it checks its changes with {@link JvmFlagPolicy#refusedChanges}); the console,
     * the setup assistant and the files are the operator's own and may.
     *
     * @return the warnings of the current snapshot, empty if there are none
     */
    public List<ConfigError> warnings() {
        return JvmFlagPolicy.warnings(current);
    }

    /** The home this service manages. */
    public CloudHome home() {
        return store.home();
    }

    /** Applies one operation; see {@link #apply(List)}. */
    public ConfigChanged apply(ConfigOperation operation) throws ConfigException, IOException {
        return apply(List.of(operation));
    }

    /**
     * Applies the operations in order, as one change: the result is validated as a whole, so an
     * intermediate state may be invalid (for example a new group and its task).
     *
     * <p>Each changed file is replaced atomically. A change that touches several files is written
     * file by file; if writing fails half way, the exception is thrown, the in-memory snapshot stays
     * at the previous state, and the next reload brings memory and disk together again.
     *
     * @return the change; {@link ConfigChanged#isEmpty()} if nothing changed (listeners are not called then)
     * @throws ConfigException if an operation cannot apply or the result is invalid; nothing is written then
     * @throws IOException     if a file cannot be written
     */
    public ConfigChanged apply(List<ConfigOperation> operations) throws ConfigException, IOException {
        Objects.requireNonNull(operations, "operations");
        lock.lock();
        try {
            ensureOpen();
            ConfigSnapshot before = current;
            ConfigSnapshot after = before;
            for (ConfigOperation operation : operations) {
                after = Objects.requireNonNull(operation.apply(after), "operation returned null");
            }
            if (mirrored) {
                requireOwnFilesOnly(before, after);
            }
            requireValid(after);
            Set<String> files = store.save(before, after, Set.of());
            current = after;
            ConfigChanged change = new ConfigChanged(before, after, files, ConfigChanged.Origin.OPERATION, operations);
            if (!change.isEmpty()) {
                notifyChanged(change);
            }
            return change;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads every file again. If they are valid and differ from the current snapshot, they become
     * current and the listeners get a {@link ConfigChanged} with {@link ConfigChanged.Origin#FILE}.
     *
     * @return the change, or empty if the files match the current snapshot
     * @throws ConfigException if a file is missing or invalid; the current snapshot stays
     */
    public Optional<ConfigChanged> reload() throws ConfigException, IOException {
        lock.lock();
        try {
            ensureOpen();
            if (mirrored) {
                return reloadMirrored();
            }
            ConfigStore.Loaded loaded = store.load();
            if (!loaded.missingFiles().isEmpty()) {
                throw new ConfigException(loaded.missingFiles().stream()
                        .map(file -> ConfigError.of(file, "", ErrorCode.FILE_MISSING)).toList());
            }
            requireValid(loaded.snapshot());
            persistLoaded(store, loaded, Set.of());
            ConfigSnapshot before = current;
            ConfigSnapshot after = loaded.snapshot();
            if (after.equals(before)) {
                return Optional.empty();
            }
            Set<String> files = changedFiles(before, after);
            current = after;
            logWarnings(after);
            ConfigChanged change = new ConfigChanged(before, after, files, ConfigChanged.Origin.FILE, List.of());
            notifyChanged(change);
            return Optional.of(change);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Switches the mirror mode of a cluster member on or off. In mirror mode {@code nodes.yml},
     * {@code groups/} and {@code tasks/} belong to the cluster head:
     * <ul>
     *   <li>{@link #apply} refuses a change to them with {@link ErrorCode#MANAGED_BY_HEAD}, so the
     *       console, the setup assistant and the REST API cannot change them here;</li>
     *   <li>{@link #reload} reads {@code cloud.yml} only and writes the mirrored files back over a
     *       hand edit, with a warning;</li>
     *   <li>only {@link #applyMirror} changes them.</li>
     * </ul>
     *
     * @param value whether this node mirrors its head
     */
    public void mirrorMode(boolean value) {
        lock.lock();
        try {
            mirrored = value;
        } finally {
            lock.unlock();
        }
    }

    /** Whether the mirror mode is on, see {@link #mirrorMode(boolean)}. */
    public boolean mirrorMode() {
        return mirrored;
    }

    /**
     * Takes over the files a cluster head mirrors: the snapshot becomes this node's own
     * {@code cloud.yml} with these nodes, groups and tasks, is validated as a whole and then written
     * (each file atomically; group and task files that are not in the set are deleted).
     *
     * @param nodes  the head's {@code nodes.yml}
     * @param groups the head's groups by name
     * @param tasks  the head's tasks by name
     * @return the change; empty if the files were the same already
     * @throws ConfigException if the result is invalid; nothing is written then
     * @throws IOException     if a file cannot be written
     * @throws IllegalStateException if the mirror mode is off
     */
    public ConfigChanged applyMirror(NodesConfig nodes, Map<String, GroupConfig> groups, Map<String, TaskConfig> tasks)
            throws ConfigException, IOException {
        Objects.requireNonNull(nodes, "nodes");
        lock.lock();
        try {
            ensureOpen();
            if (!mirrored) {
                throw new IllegalStateException("the mirror mode is off");
            }
            ConfigSnapshot before = current;
            ConfigSnapshot after = ConfigSnapshot.of(before.cloud(), nodes, groups, tasks);
            requireValid(after);
            Set<String> files = new LinkedHashSet<>(store.writeMirrored(after));
            files.addAll(changedFiles(before, after));
            current = after;
            ConfigChanged change = new ConfigChanged(before, after, files, ConfigChanged.Origin.MIRROR, List.of());
            if (!after.equals(before)) {
                logWarnings(after);
                notifyChanged(change);
            }
            return change;
        } finally {
            lock.unlock();
        }
    }

    /** Mirror mode: {@code cloud.yml} from disk, the mirrored files written back over any hand edit. */
    private Optional<ConfigChanged> reloadMirrored() throws ConfigException, IOException {
        CloudConfig cloud = store.loadCloud();
        ConfigSnapshot before = current;
        ConfigSnapshot after = before.withCloud(cloud);
        requireValid(after);
        Set<String> restored = store.writeMirrored(after);
        if (!restored.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "{0} managed by the cluster head; the hand edit is undone. Change "
                    + "them on the head (docs/cloud/cluster.md).", String.join(", ", restored));
        }
        if (after.equals(before)) {
            return Optional.empty();
        }
        current = after;
        logWarnings(after);
        ConfigChanged change = new ConfigChanged(before, after, Set.of(CloudHome.CLOUD_FILE), ConfigChanged.Origin.FILE,
                List.of());
        notifyChanged(change);
        return Optional.of(change);
    }

    /** Mirror mode: a change must leave {@code nodes.yml}, the groups and the tasks as they are. */
    private static void requireOwnFilesOnly(ConfigSnapshot before, ConfigSnapshot after) throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        for (String file : changedFiles(before, after)) {
            if (!file.equals(CloudHome.CLOUD_FILE)) {
                errors.add(ConfigError.of(file, "", ErrorCode.MANAGED_BY_HEAD));
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
    }

    /**
     * Starts reloading the files when they change on disk. Idempotent.
     */
    public void startWatching() throws IOException {
        lock.lock();
        try {
            ensureOpen();
            if (watcher == null) {
                watcher = ConfigWatcher.start(store.home(), this::reloadFromWatcher,
                        (folder, error) -> notifyFailed(new ConfigException(
                                ConfigError.of(folder, "", ErrorCode.FILE_ACCESS), error)),
                        QUIET_PERIOD);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Registers a listener. */
    public void addListener(ConfigListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Removes a listener. */
    public void removeListener(ConfigListener listener) {
        listeners.remove(listener);
    }

    /** Stops the file watcher. Further changes fail with {@link IllegalStateException}. */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            closed = true;
            if (watcher != null) {
                watcher.close();
                watcher = null;
            }
        } finally {
            lock.unlock();
        }
    }

    private void reloadFromWatcher() {
        try {
            reload();
        } catch (ConfigException e) {
            LOG.log(System.Logger.Level.WARNING, "Configuration not reloaded, keeping the previous one:{0}{1}",
                    System.lineSeparator(), e.describe(current.cloud().language()));
            notifyFailed(e);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Configuration not reloaded, keeping the previous one", e);
            notifyFailed(new ConfigException(ConfigError.of(".", "", ErrorCode.FILE_ACCESS), e));
        } catch (IllegalStateException e) {
            // closed while an event was pending
        }
    }

    private void notifyChanged(ConfigChanged change) {
        dispatch(() -> deliverChanged(change));
    }

    private void notifyFailed(ConfigException error) {
        lock.lock();
        try {
            dispatch(() -> deliverFailed(error));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Queues an event and, unless a delivery is already running on this thread (a listener called
     * {@link #apply}), delivers every queued event in order. Called with {@link #lock} held.
     */
    private void dispatch(Runnable event) {
        pendingEvents.add(event);
        if (dispatching) {
            return;
        }
        dispatching = true;
        try {
            Runnable next;
            while ((next = pendingEvents.poll()) != null) {
                next.run();
            }
        } finally {
            dispatching = false;
            pendingEvents.clear(); // only non-empty if an Error escaped a listener
        }
    }

    private void deliverChanged(ConfigChanged change) {
        for (ConfigListener listener : listeners) {
            try {
                listener.configChanged(change);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A configuration listener failed", e);
            }
        }
    }

    private void deliverFailed(ConfigException error) {
        for (ConfigListener listener : listeners) {
            try {
                listener.reloadFailed(error);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A configuration listener failed", e);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("configuration service is closed");
        }
    }

    private static void logWarnings(ConfigSnapshot snapshot) {
        List<ConfigError> warnings = JvmFlagPolicy.warnings(snapshot);
        if (!warnings.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (ConfigError warning : warnings) {
                text.append(System.lineSeparator()).append(warning.describe(snapshot.cloud().language()));
            }
            LOG.log(System.Logger.Level.WARNING, "JVM flags outside the allowlist (kept, because they are in the file):{0}",
                    text);
        }
    }

    private static void requireValid(ConfigSnapshot snapshot) throws ConfigException {
        List<ConfigError> errors = ConfigValidator.validate(snapshot);
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
    }

    /** Writes missing default files and rewrites migrated files, keeping a backup of the old ones. */
    private static void persistLoaded(ConfigStore store, ConfigStore.Loaded loaded, Set<String> missing)
            throws IOException {
        Set<String> force = new LinkedHashSet<>(missing);
        for (Map.Entry<String, Integer> entry : loaded.migrated().entrySet()) {
            store.backup(entry.getKey(), entry.getValue());
            force.add(entry.getKey());
        }
        if (!force.isEmpty()) {
            store.save(loaded.snapshot(), loaded.snapshot(), force);
        }
    }

    private static Set<String> changedFiles(ConfigSnapshot before, ConfigSnapshot after) {
        Map<String, Object> a = ConfigStore.files(before);
        Map<String, Object> b = ConfigStore.files(after);
        Set<String> files = new LinkedHashSet<>();
        b.forEach((file, value) -> {
            if (!value.equals(a.get(file))) {
                files.add(file);
            }
        });
        a.keySet().stream().filter(file -> !b.containsKey(file)).forEach(files::add);
        return files;
    }
}
