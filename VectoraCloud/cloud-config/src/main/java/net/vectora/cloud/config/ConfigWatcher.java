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

/**
 * Watches {@code cloud.yml}, {@code nodes.yml}, {@code groups/} and {@code tasks/} and calls back
 * once per burst of changes. Editors often write a file in several steps (truncate, write, rename),
 * so events are collected until nothing has happened for a short quiet period.
 *
 * <p>Uses the platform {@link WatchService}: native on Linux and Windows, polling (a few seconds
 * delay) on macOS.
 */
final class ConfigWatcher implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ConfigWatcher.class.getName());

    private final WatchService watchService;
    private final Runnable onChange;
    private final long quietMillis;
    private final Thread thread;

    private ConfigWatcher(WatchService watchService, Runnable onChange, Duration quietPeriod) {
        this.watchService = watchService;
        this.onChange = onChange;
        this.quietMillis = quietPeriod.toMillis();
        this.thread = Thread.ofPlatform().daemon().name("vectora-config-watcher").unstarted(this::run);
    }

    /** Starts watching {@code home}; {@code onChange} runs on the watcher thread. */
    static ConfigWatcher start(CloudHome home, Runnable onChange, Duration quietPeriod) throws IOException {
        WatchService service = home.root().getFileSystem().newWatchService();
        try {
            for (Path dir : new Path[] {home.root(), home.groupsDir(), home.tasksDir()}) {
                Files.createDirectories(dir);
                dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }
        } catch (IOException | RuntimeException e) {
            service.close();
            throw e;
        }
        ConfigWatcher watcher = new ConfigWatcher(service, onChange, quietPeriod);
        watcher.thread.start();
        return watcher;
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

    /** Whether the key holds an event for a configuration file; resets the key. */
    private static boolean drain(WatchKey key) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                relevant = true;
            } else if (event.context() instanceof Path path) {
                String name = path.getFileName().toString();
                relevant |= !name.startsWith(".") && (name.endsWith(".yml") || name.endsWith(".yaml"));
            }
        }
        key.reset();
        return relevant;
    }

    @Override
    public void close() throws IOException {
        watchService.close();
        thread.interrupt();
    }
}
