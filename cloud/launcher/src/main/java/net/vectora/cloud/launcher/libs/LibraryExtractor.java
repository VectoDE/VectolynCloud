package net.vectora.cloud.launcher.libs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Unpacks the embedded libraries to {@code <home>/storage/libs/<version>} and checks each one against the
 * SHA-256 in the {@link LibraryIndex}.
 *
 * <ul>
 *   <li>A jar already in the cache is reused only if its hash still matches; otherwise it is
 *       unpacked again. A tampered or half-written cache therefore never gets loaded.</li>
 *   <li>New files are written to a temporary file in the same directory, hashed while writing,
 *       and moved into place only after the hash matched.</li>
 *   <li>Every target path is resolved and checked to stay directly inside the cache directory
 *       (zip slip), in addition to the file name check of the index.</li>
 *   <li>Jars in the cache that the index no longer lists (older versions) are deleted.</li>
 * </ul>
 */
public final class LibraryExtractor {

    /** Source of the embedded jars, normally the class loader of {@code VectoraCloud.jar}. */
    @FunctionalInterface
    public interface Source {

        /**
         * Opens an embedded jar.
         *
         * @param fileName file name from the index
         * @return a stream, or {@code null} if there is no such jar
         * @throws IOException if opening fails
         */
        InputStream open(String fileName) throws IOException;
    }

    private final Source source;
    private final Path directory;

    /**
     * Creates an extractor.
     *
     * @param source    where the embedded jars come from
     * @param directory target directory, normally {@code <home>/storage/libs/<version>}
     */
    public LibraryExtractor(Source source, Path directory) {
        this.source = source;
        this.directory = directory.toAbsolutePath().normalize();
    }

    /**
     * Unpacks and checks every library of the index.
     *
     * @param index the index
     * @return paths of the checked jars, in index order
     * @throws LibraryException if a jar is missing, has the wrong hash, or would leave the directory
     */
    public List<Path> extract(LibraryIndex index) throws LibraryException {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new LibraryException(LibraryException.Kind.IO, directory.toString(), "cannot create directory", e);
        }
        List<Path> result = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        for (LibraryEntry entry : index.entries()) {
            Path target = resolveInside(entry.fileName());
            wanted.add(target.getFileName().toString().toLowerCase(Locale.ROOT));
            if (!isCachedAndValid(target, entry)) {
                unpack(entry, target);
            }
            result.add(target);
        }
        deleteStale(wanted);
        return result;
    }

    /**
     * Resolves a file name inside the target directory and refuses anything that would end up
     * elsewhere.
     *
     * @param fileName file name from the index
     * @return the absolute target path
     * @throws LibraryException if the name is unsafe
     */
    Path resolveInside(String fileName) throws LibraryException {
        if (!LibraryIndex.isSafeName(fileName)) {
            throw new LibraryException(LibraryException.Kind.UNSAFE_NAME, fileName, "not a plain jar file name");
        }
        return requireDirectChild(directory, directory.resolve(fileName));
    }

    /**
     * Second line of defence behind {@link LibraryIndex#isSafeName(String)}: the normalized target
     * must lie directly inside the directory, not above it and not in a subdirectory.
     *
     * @param dir    the normalized absolute target directory
     * @param target the path resolved against {@code dir}
     * @return the normalized target
     * @throws LibraryException if the target is not a direct child of {@code dir}
     */
    static Path requireDirectChild(Path dir, Path target) throws LibraryException {
        Path normalized = target.normalize();
        if (!dir.equals(normalized.getParent())) {
            throw new LibraryException(LibraryException.Kind.UNSAFE_NAME, target.toString(),
                    "resolves outside " + dir);
        }
        return normalized;
    }

    private static boolean isCachedAndValid(Path target, LibraryEntry entry) {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(target)) {
            return entry.sha256().equals(sha256(in));
        } catch (IOException e) {
            return false;
        }
    }

    private void unpack(LibraryEntry entry, Path target) throws LibraryException {
        Path temp = null;
        try (InputStream raw = source.open(entry.fileName())) {
            if (raw == null) {
                throw new LibraryException(LibraryException.Kind.MISSING, entry.fileName(), "not embedded in VectoraCloud.jar");
            }
            temp = Files.createTempFile(directory, ".unpack-", ".tmp");
            MessageDigest digest = newDigest();
            try (DigestInputStream in = new DigestInputStream(raw, digest);
                 OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!entry.sha256().equals(actual)) {
                throw new LibraryException(LibraryException.Kind.HASH_MISMATCH, entry.fileName(),
                        "expected " + entry.sha256() + ", got " + actual);
            }
            move(temp, target);
            temp = null;
        } catch (IOException e) {
            throw new LibraryException(LibraryException.Kind.IO, entry.fileName(), e.toString(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort; a leftover .tmp file is harmless and removed on the next start.
                }
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteStale(Set<String> wanted) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean staleJar = lower.endsWith(".jar") && !wanted.contains(lower);
                boolean leftover = name.startsWith(".unpack-") && lower.endsWith(".tmp");
                if ((staleJar || leftover) && Files.isRegularFile(file)) {
                    try {
                        Files.delete(file);
                    } catch (IOException ignored) {
                        // Still in use by another running cloud on Windows; tried again next start.
                    }
                }
            }
        } catch (IOException ignored) {
            // Cleaning up is optional; the checked jars are already in place.
        }
    }

    /**
     * Computes the SHA-256 of a stream.
     *
     * @param in the stream, read to the end but not closed
     * @return 64 lower-case hex digits
     * @throws IOException if reading fails
     */
    public static String sha256(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java SE specification", e);
        }
    }
}
