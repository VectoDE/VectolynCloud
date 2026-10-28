package net.vectora.cloud.config;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Watches {@code cloud.yml}, {@code nodes.yml}, {@code groups/} and {@code tasks/} and calls back
 * once per burst of changes. Editors often write a file in several steps (truncate, write, rename),
 * so events are collected until nothing has happened for a short quiet period.
 *
 * <p>Uses the platform {@link WatchService}: native on Linux and Windows, polling (a few seconds
 * delay) on macOS.
 *
 * <p>A watch key becomes invalid when its folder is deleted, renamed or unmounted
 * ({@link WatchKey#reset()} returns {@code false}); without care the watcher would silently stop
 * seeing changes there. The watcher then creates the folder again if needed, registers it again and
 * triggers a reload. If that fails, it reports the folder through {@code onError} and keeps
 * watching the other folders.
 */
final class ConfigWatcher implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ConfigWatcher.class.getName());

    private final WatchService watchService;
    private final Runnable onChange;
    private final BiConsumer<String, IOException> onError;
    private final Path root;
    private final long quietMillis;
    private final Thread thread;

    private ConfigWatcher(WatchService watchService, Path root, Runnable onChange,
                          BiConsumer<String, IOException> onError, Duration quietPeriod) {
        this.watchService = watchService;
        this.root = root;
        this.onChange = onChange;
        this.onError = onError;
        this.quietMillis = quietPeriod.toMillis();
        this.thread = Thread.ofPlatform().daemon().name("vectora-config-watcher").unstarted(this::run);
    }

    /**
     * Starts watching {@code home}. Both callbacks run on the watcher thread.
     *
     * @param onChange runs once per burst of relevant changes
     * @param onError  gets the folder (relative to the home, {@code groups/}, or {@code .} for the home
     *                 itself) that could not be watched again after its key became invalid
     */
    static ConfigWatcher start(CloudHome home, Runnable onChange, BiConsumer<String, IOException> onError,
                               Duration quietPeriod) throws IOException {
        WatchService service = home.root().getFileSystem().newWatchService();
        try {
            for (Path dir : new Path[] {home.root(), home.groupsDir(), home.tasksDir()}) {
                Files.createDirectories(dir);
                register(service, dir);
            }
        } catch (IOException | RuntimeException e) {
            service.close();
            throw e;
        }
        ConfigWatcher watcher = new ConfigWatcher(service, home.root(), onChange, onError, quietPeriod);
        watcher.thread.start();
        return watcher;
    }

    private static void register(WatchService service, Path dir) throws IOException {
        dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
    }

    private void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                boolean relevant = drain(watchService.take());
                WatchKey next;
                while ((next = watchService.poll(quietMillis, TimeUnit.MILLISECONDS)) != null) {
                    relevant |= drain(next);
                }
                if (relevant) {
                    try {
                        onChange.run();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "Reloading the configuration failed", e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // closed: stop quietly
        }
    }

    /**
     * Whether the key holds an event for a configuration file; resets the key. An invalid key (its
     * folder is gone) is replaced and always counts as relevant, because files may have vanished.
     */
    private boolean drain(WatchKey key) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                relevant = true;
            } else if (event.context() instanceof Path path) {
                String name = path.getFileName().toString();
                relevant |= !name.startsWith(".") && (name.endsWith(".yml") || name.endsWith(".yaml"));
            }
        }
        if (!key.reset()) {
            watchAgain((Path) key.watchable());
            return true;
        }
        return relevant;
    }

    /**
     * Registers a folder whose key became invalid again. A missing {@code groups/} or {@code tasks/}
     * is created first (as on startup); the home itself is never created, because a missing home
     * means the storage is gone, which the following reload reports. Package-private for tests.
     */
    void watchAgain(Path dir) {
        String name = dir.equals(root) ? "." : root.relativize(dir).toString().replace('\\', '/') + "/";
        try {
            if (!dir.equals(root)) {
                Files.createDirectories(dir);
            }
            register(watchService, dir);
            LOG.log(System.Logger.Level.INFO, "Watching {0} again after it was removed or replaced", name);
        } catch (ClosedWatchServiceException e) {
            // closing: nothing to watch any more
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot watch " + name + " again; hand edits there are not picked up", e);
            onError.accept(name, e);
        }
    }

    @Override
    public void close() throws IOException {
        watchService.close();
        thread.interrupt();
    }
}
