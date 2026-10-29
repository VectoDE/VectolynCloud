package net.vectora.cloud.backup.archive;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.vectora.cloud.backup.manifest.BackupManifest;
import net.vectora.cloud.backup.manifest.BackupManifest.FileEntry;
import net.vectora.cloud.backup.manifest.ManifestCodec;
import net.vectora.cloud.backup.manifest.ManifestPaths;

/**
 * Writes one backup archive: a zip with every file hashed (SHA-256) while it is copied, and
 * {@code manifest.json} as the last entry. Zip64 is used automatically for large archives.
 *
 * <p>The file is written in place; the caller writes to a temporary name and renames it once
 * {@link #finish(BackupManifest)} returned, so a half-written archive never carries the final name.
 */
public final class ArchiveWriter implements Closeable {

    private final FileChannel channel;
    private final ZipOutputStream zip;
    private final byte[] buffer = new byte[64 * 1024];
    private boolean finished;

    /**
     * Opens the archive.
     *
     * @param file an existing, empty file (already restricted by the caller)
     * @param level deflate level, 0 (store) to 9
     * @throws IOException if it cannot be opened
     */
    public ArchiveWriter(Path file, int level) throws IOException {
        if (level < Deflater.NO_COMPRESSION || level > Deflater.BEST_COMPRESSION) {
            throw new IllegalArgumentException("compression level must be 0 to 9");
        }
        FileChannel opened = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
        try {
            // One chain: closing the zip stream closes the buffer and the channel.
            ZipOutputStream stream = new ZipOutputStream(
                    new BufferedOutputStream(Channels.newOutputStream(opened), 256 * 1024));
            stream.setLevel(level);
            this.channel = opened;
            this.zip = stream;
        } catch (RuntimeException | Error e) {
            opened.close();
            throw e;
        }
    }

    /**
     * Adds a file.
     *
     * @param entry its path in the archive; must satisfy {@link ManifestPaths#isSafe(String)}
     * @param source the file to read; links are not followed
     * @return path, size and hash for the manifest
     * @throws UnreadableFileException if the file cannot be opened or its first bytes cannot be
     *     read (for example locked by another process); nothing was written, the archive stays usable
     * @throws IOException if reading or writing fails later; the archive is unusable afterwards
     */
    public FileEntry add(String entry, Path source) throws IOException {
        if (!ManifestPaths.isSafe(entry) || entry.equals(BackupManifest.ENTRY)) {
            throw new IllegalArgumentException("unsafe archive path");
        }
        MessageDigest digest = sha256();
        ZipEntry zipEntry = new ZipEntry(entry);
        long size = 0;
        InputStream in;
        int n;
        try {
            FileTime modified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);
            zipEntry.setLastModifiedTime(modified);
            in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new UnreadableFileException(e);
        }
        try (in) {
            // Read the first block before the entry exists, so a locked file can still be skipped.
            try {
                n = in.read(buffer);
            } catch (IOException e) {
                throw new UnreadableFileException(e);
            }
            zip.putNextEntry(zipEntry);
            for (; n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
                zip.write(buffer, 0, n);
                size += n;
            }
            zip.closeEntry();
        }
        return new FileEntry(entry, size, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Writes the manifest as the last entry and closes the archive, flushed to disk.
     *
     * @param manifest the manifest
     * @throws IOException if writing fails
     */
    public void finish(BackupManifest manifest) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        byte[] content = ManifestCodec.write(manifest);
        zip.putNextEntry(new ZipEntry(BackupManifest.ENTRY));
        zip.write(content);
        zip.closeEntry();
        zip.finish();
        zip.flush();
        channel.force(true);
        finished = true;
        zip.close();
    }

    /** Closes the archive; without {@link #finish} it has no manifest and is not a valid backup. */
    @Override
    public void close() throws IOException {
        if (!finished) {
            zip.close();
        }
    }

    /** A source file could not be read at all; the archive is unchanged and can go on. */
    public static final class UnreadableFileException extends IOException {

        private static final long serialVersionUID = 1L;

        UnreadableFileException(IOException cause) {
            super(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }

    /** A new SHA-256 digest. */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }
}
