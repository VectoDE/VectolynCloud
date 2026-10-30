package net.vectolyn.cloud.node;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import net.vectolyn.cloud.config.io.RegularPaths;

/**
 * Makes sure only one cloud process uses a home: an exclusive lock on {@code state/cloud.lock},
 * held as long as the node runs. Two clouds on one home would start every server twice, fight
 * over the worlds and ports, and delete each other's download and JRE leftovers.
 *
 * <p>The lock belongs to the operating system: it is released when the process ends, also after a
 * crash or {@code kill -9}, so a stale file never blocks the next start. The file itself stays and
 * holds nothing.
 */
public final class HomeLock implements AutoCloseable {

    /** Lock file, relative to the home. */
    public static final String FILE = "state/cloud.lock";

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private HomeLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /** Thrown when another cloud process (or another node in this JVM) already uses the home. */
    public static final class HomeLockedException extends IOException {

        private static final long serialVersionUID = 1L;

        HomeLockedException(Path home) {
            super("Another VectolynCloud already runs with the home " + home + " (it holds " + FILE
                    + "). Only one cloud may use a home at a time; stop the other one first.");
        }
    }

    /**
     * Takes the lock of a home.
     *
     * @param home the cloud home
     * @return the held lock; close it when the node ends
     * @throws HomeLockedException if another process or another node of this JVM holds it
     * @throws IOException if {@code state/} or the lock file cannot be created, or is a link
     */
    public static HomeLock acquire(Path home) throws IOException {
        Objects.requireNonNull(home, "home");
        Path file = home.resolve(FILE);
        RegularPaths.requireDirectoryOrMissing(file.getParent());
        Files.createDirectories(file.getParent());
        RegularPaths.requireFileOrMissing(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new HomeLockedException(home);
        }
        return new HomeLock(file, channel, lock);
    }

    /**
     * Returns the lock file.
     *
     * @return {@code <home>/state/cloud.lock}
     */
    public Path file() {
        return file;
    }

    /**
     * Tells whether the lock is still held.
     *
     * @return whether it is valid
     */
    public boolean isHeld() {
        return lock.isValid();
    }

    /** Releases the lock. Safe to call more than once. */
    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
