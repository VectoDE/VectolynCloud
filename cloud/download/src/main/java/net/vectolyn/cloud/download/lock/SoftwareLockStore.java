package net.vectolyn.cloud.download.lock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Shared access to one {@code software.lock.yml}. Every change is a read, a change and an atomic
 * write under one lock, so components that pin artifacts at the same time (server software, plugins,
 * JREs) never overwrite each other's entries. The lock is per file and covers every store object
 * on that file in this JVM.
 */
public final class SoftwareLockStore {

    private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final ReentrantLock lock;

    /**
     * Creates a store.
     *
     * @param file the path of {@code software.lock.yml}
     */
    public SoftwareLockStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.lock = LOCKS.computeIfAbsent(this.file, key -> new ReentrantLock());
    }

    /**
     * Creates the store of a cloud home.
     *
     * @param cloudHome the cloud home
     * @return the store for {@code <home>/config/software.lock.yml}
     */
    public static SoftwareLockStore inHome(Path cloudHome) {
        return new SoftwareLockStore(cloudHome.resolve(SoftwareLockFile.CONFIG_DIR).resolve(SoftwareLockFile.FILE_NAME));
    }

    /**
     * Returns the file.
     *
     * @return the absolute path of the lockfile
     */
    public Path file() {
        return file;
    }

    /**
     * Reads the current lock.
     *
     * @return the lock; empty if the file does not exist
     * @throws IOException if the file cannot be read or is invalid
     */
    public SoftwareLock read() throws IOException {
        lock.lock();
        try {
            return SoftwareLockFile.read(file);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads one entry.
     *
     * @param name the entry name
     * @return the entry, or empty
     * @throws IOException if the file cannot be read or is invalid
     */
    public Optional<LockedArtifact> get(String name) throws IOException {
        return read().get(name);
    }

    /**
     * Changes the lock: reads the file, applies the change and writes the result if it differs.
     *
     * @param change the change
     * @return the lock after the change
     * @throws IOException if reading or writing fails; the file is then unchanged
     */
    public SoftwareLock update(UnaryOperator<SoftwareLock> change) throws IOException {
        lock.lock();
        try {
            SoftwareLock before = SoftwareLockFile.read(file);
            SoftwareLock after = Objects.requireNonNull(change.apply(before), "changed lock");
            if (!after.equals(before)) {
                SoftwareLockFile.write(file, after);
            }
            return after;
        } finally {
            lock.unlock();
        }
    }
}
