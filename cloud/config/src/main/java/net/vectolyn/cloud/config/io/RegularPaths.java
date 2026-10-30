package net.vectolyn.cloud.config.io;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The symbolic link rule of the cloud home, in one place.
 *
 * <p>Configuration files ({@code cloud.yml}, {@code nodes.yml}, {@code groups/*.yml},
 * {@code tasks/*.yml}), the folders {@code groups/}, {@code tasks/} and {@code secrets/}, and
 * {@code secrets/vectolyn.env} must be real files and folders. A symbolic link, a Windows junction or
 * a special file in their place is an error: it is never followed, never skipped silently and never
 * replaced by a write. The home folder itself may be a link (for example a mounted volume).
 *
 * <p>Why: the cloud replaces files atomically by renaming a new file over the old one, which would
 * turn a link into a plain file, and a link could point a secret or configuration out of the home.
 * Checks use {@link LinkOption#NOFOLLOW_LINKS}; a junction then shows up as "other", not as folder.
 */
public final class RegularPaths {

    private RegularPaths() {
    }

    /** What is at a path, without following links. */
    public enum Kind {
        /** Nothing there (a dangling link is {@link #OTHER}). */
        MISSING,
        /** A real file. */
        FILE,
        /** A real folder. */
        DIRECTORY,
        /** A symbolic link, junction, device or other special file. */
        OTHER
    }

    /** Classifies {@code path} without following links. */
    public static Kind kind(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return Kind.MISSING;
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            return Kind.OTHER;
        }
        if (attributes.isDirectory()) {
            return Kind.DIRECTORY;
        }
        return attributes.isRegularFile() ? Kind.FILE : Kind.OTHER;
    }

    /**
     * Fails unless {@code path} is missing or a real file.
     *
     * @throws FileSystemException naming the path, if it is a link, folder or special file
     */
    public static void requireFileOrMissing(Path path) throws IOException {
        Kind kind = kind(path);
        if (kind != Kind.MISSING && kind != Kind.FILE) {
            throw new FileSystemException(path.toString(), null,
                    "must be a regular file, not a symbolic link, junction or folder");
        }
    }

    /**
     * Fails unless {@code path} is missing or a real folder.
     *
     * @throws FileSystemException naming the path, if it is a link, junction or file
     */
    public static void requireDirectoryOrMissing(Path path) throws IOException {
        Kind kind = kind(path);
        if (kind != Kind.MISSING && kind != Kind.DIRECTORY) {
            throw new FileSystemException(path.toString(), null,
                    "must be a regular folder, not a symbolic link, junction or file");
        }
    }
}
