package net.vectolyn.cloud.config.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Replaces a file so that readers see either the old or the new content, never a half-written file.
 *
 * <p>Steps: write a temporary file in the same directory, flush it to disk, then rename it over the
 * target with {@link StandardCopyOption#ATOMIC_MOVE}. If anything fails before the rename, the
 * target is untouched and the temporary file is deleted.
 *
 * <p>Fallback: when the file system cannot rename atomically
 * ({@link AtomicMoveNotSupportedException}, seen on some network and FUSE file systems), the file is
 * moved with {@link StandardCopyOption#REPLACE_EXISTING} only. That is still a rename on the same
 * directory in practice, but the platform no longer promises atomicity, so a crash at exactly that
 * moment may leave the target missing; the complete new content is then still in the temporary
 * file next to it. On Windows a rename can fail briefly while another process (an editor, a virus
 * scanner) has the target open; such {@link AccessDeniedException}s are retried a few times.
 */
public final class AtomicFileWriter {

    /** Moves a file; replaced in tests to simulate failures. */
    @FunctionalInterface
    public interface Mover {
        /** Same contract as {@link Files#move(Path, Path, CopyOption...)}. */
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    /** Runs on the temporary file before content is written, for example to restrict its permissions. */
    @FunctionalInterface
    public interface TempFileHook {
        /** Prepares {@code temp}. */
        void prepare(Path temp) throws IOException;
    }

    private static final int RENAME_ATTEMPTS = 5;

    private final Mover mover;

    /** Writer that uses {@link Files#move}. */
    public AtomicFileWriter() {
        this(Files::move);
    }

    /** Writer with a custom move step (tests). */
    public AtomicFileWriter(Mover mover) {
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    /** Atomically replaces {@code target} with {@code content}. */
    public void write(Path target, byte[] content) throws IOException {
        write(target, content, temp -> { });
    }

    /**
     * Atomically replaces {@code target} with {@code content}; {@code hook} runs on the empty
     * temporary file first.
     */
    public void write(Path target, byte[] content, TempFileHook hook) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "." + absolute.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            hook.prepare(temp);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            rename(temp, absolute);
            moved = true;
            syncDirectory(dir);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void rename(Path temp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    mover.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    mover.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= RENAME_ATTEMPTS) {
                    throw e;
                }
                pause(attempt * 25L);
            }
        }
    }

    private static void pause(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while retrying a rename");
        }
    }

    /** Makes the rename durable on POSIX systems; Windows cannot open directories and does not need it. */
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Not possible on this platform; the rename itself has already happened.
        }
    }
}
