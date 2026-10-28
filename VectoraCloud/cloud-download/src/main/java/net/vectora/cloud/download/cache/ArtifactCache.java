package net.vectora.cloud.download.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.vectora.cloud.download.ChecksumMismatchException;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.hash.MultiDigest;

/**
 * Content-addressed store under {@code cache/artifacts/}: each file is named by its SHA-256.
 *
 * <p>A file only gets its final name after its checksum was verified, so whatever lies under a
 * hash name was correct when it was stored. On lookup it is hashed again; a file that no longer
 * matches (disk error, manual edit) is deleted and downloaded anew.
 *
 * <p>Downloads in progress live in {@code download-*.part} files. A crash or a killed process can
 * leave such files behind. {@link #removeStalePartFiles()} deletes every one that no download in
 * this JVM is writing; {@link ArtifactDownloader} calls it before each download. Only one cloud
 * process may use a cloud home at a time, so a part file that this JVM does not know is orphaned.
 */
public final class ArtifactCache {

    /** Suffix of files that are still being downloaded. */
    public static final String PART_SUFFIX = ".part";

    /** Prefix of files that are still being downloaded. */
    public static final String PART_PREFIX = "download-";

    /** Part files of downloads that are running in this JVM, across all cache objects. */
    private static final Set<Path> ACTIVE_PARTS = ConcurrentHashMap.newKeySet();

    private final Path directory;

    /**
     * Creates the cache.
     *
     * @param cacheRoot the cloud's {@code cache/} directory; files go to {@code artifacts/} below it
     */
    public ArtifactCache(Path cacheRoot) {
        this.directory = Objects.requireNonNull(cacheRoot, "cacheRoot").resolve("artifacts");
    }

    /**
     * Returns the directory of the stored files.
     *
     * @return {@code cache/artifacts}
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns where a file with this SHA-256 is stored.
     *
     * @param sha256 the content hash
     * @return the path, which may not exist yet
     */
    public Path pathFor(Checksum sha256) {
        if (sha256.algorithm() != HashAlgorithm.SHA256) {
            throw new IllegalArgumentException("the cache is addressed by SHA-256");
        }
        return directory.resolve(sha256.hex());
    }

    /**
     * Looks up a stored file and verifies it.
     *
     * @param sha256 the content hash
     * @param also another checksum the file must match as well, or {@code null}
     * @return the file if it exists and matches; empty otherwise (a mismatching file is deleted)
     * @throws IOException if the file cannot be read or deleted
     */
    public Optional<Path> lookup(Checksum sha256, Checksum also) throws IOException {
        Path file = pathFor(sha256);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        List<HashAlgorithm> algorithms = also == null ? List.of() : List.of(also.algorithm());
        Map<HashAlgorithm, Checksum> actual = MultiDigest.of(file, algorithms);
        if (actual.get(HashAlgorithm.SHA256).matches(sha256) && (also == null || actual.get(also.algorithm()).matches(also))) {
            return Optional.of(file);
        }
        Files.deleteIfExists(file);
        return Optional.empty();
    }

    /**
     * Creates an empty {@code .part} file for a download in the cache directory, so the final move
     * stays on the same file system and can be atomic.
     *
     * @return the new file
     * @throws IOException if it cannot be created
     */
    public Path newPartFile() throws IOException {
        Files.createDirectories(directory);
        Path part = Files.createTempFile(directory, PART_PREFIX, PART_SUFFIX);
        ACTIVE_PARTS.add(key(part));
        return part;
    }

    /**
     * Deletes a {@code .part} file that will not be committed, for example after a failed download.
     *
     * @param part the file from {@link #newPartFile()}
     * @throws IOException if it cannot be deleted
     */
    public void discard(Path part) throws IOException {
        try {
            Files.deleteIfExists(part);
        } finally {
            ACTIVE_PARTS.remove(key(part));
        }
    }

    /**
     * Deletes orphaned {@code download-*.part} files: those that no download in this JVM is
     * writing, left behind by a crash or a killed process.
     *
     * @return how many files were deleted
     * @throws IOException if the directory cannot be read or a file cannot be deleted
     */
    public int removeStalePartFiles() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(directory, PART_PREFIX + "*" + PART_SUFFIX)) {
            for (Path part : parts) {
                if (!ACTIVE_PARTS.contains(key(part)) && Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)
                        && Files.deleteIfExists(part)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static Path key(Path part) {
        return part.toAbsolutePath().normalize();
    }

    /**
     * Moves a verified {@code .part} file to its hash name. If another download stored the same
     * content in the meantime, the part file is dropped and the existing file is kept.
     *
     * @param part the verified file
     * @param sha256 its content hash
     * @return the final path
     * @throws IOException if the move fails
     */
    public Path commit(Path part, Checksum sha256) throws IOException {
        Path target = pathFor(sha256);
        try {
            try {
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, target);
                }
            } catch (FileAlreadyExistsException e) {
                Files.deleteIfExists(part);
            }
            if (Files.exists(part)) {
                // Some platforms replace instead of failing; either way only one file remains.
                Files.deleteIfExists(part);
            }
        } finally {
            ACTIVE_PARTS.remove(key(part));
        }
        return target;
    }

    /**
     * Copies a stored file to its place in a server directory, through a temporary file and an
     * atomic move, so a running server never sees half a jar. The SHA-256 is computed again while
     * copying: a cache file that changed since it was stored (disk error, manual edit) is deleted,
     * and the destination stays as it was.
     *
     * @param sha256 the content hash
     * @param destination the target path, for example {@code runtime/paper.jar}
     * @throws ChecksumMismatchException if the cached file no longer has this hash
     * @throws IOException if the file is not in the cache or the copy fails
     */
    public void copyTo(Checksum sha256, Path destination) throws IOException {
        Path source = pathFor(sha256);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(source.toString(), null, "not in the artifact cache");
        }
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString() + ".", ".tmp");
        try {
            MultiDigest digest = new MultiDigest(List.of());
            try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                    OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            Checksum actual = digest.finish().get(HashAlgorithm.SHA256);
            if (!actual.matches(sha256)) {
                Files.deleteIfExists(source);
                throw new ChecksumMismatchException("cache file " + sha256.hex() + " has changed on disk (now "
                        + actual + "). It was deleted and must be downloaded again; " + absolute.getFileName()
                        + " was not replaced.");
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
