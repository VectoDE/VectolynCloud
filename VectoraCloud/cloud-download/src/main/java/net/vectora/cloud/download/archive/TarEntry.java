package net.vectora.cloud.download.archive;

import java.util.Objects;

/**
 * One entry of a TAR archive as {@link TarReader} returns it.
 *
 * @param name the path inside the archive, as stored (not yet checked against a target)
 * @param type the entry type
 * @param size the content size in bytes; 0 for everything but files
 * @param mode the permission bits (lower 12 bits of the stored mode)
 * @param linkName the link target for symlinks and hard links, otherwise empty
 */
public record TarEntry(String name, Type type, long size, int mode, String linkName) {

    /** The entry types the reader supports. Everything else is refused. */
    public enum Type {
        /** A regular file. */
        FILE,
        /** A directory. */
        DIRECTORY,
        /** A symbolic link. */
        SYMLINK,
        /** A hard link to an earlier entry. */
        HARDLINK
    }

    /**
     * Checks the values.
     *
     * @param name the path
     * @param type the type
     * @param size the size
     * @param mode the mode
     * @param linkName the link target
     */
    public TarEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(linkName, "linkName");
        if (size < 0) {
            throw new IllegalArgumentException("negative size");
        }
    }
}
