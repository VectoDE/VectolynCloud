package net.vectora.cloud.template;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Safe writes below a runtime folder.
 *
 * <p>Relative paths use {@code /} as separator and must stay inside the runtime folder: no
 * {@code ..}, no {@code .}, no empty parts, no absolute paths, no drive letters, no backslashes, no
 * control characters. Symbolic links and other reparse points (Windows junctions) below the runtime
 * folder are never followed: a link where a folder or the target file should be is an error. The
 * runtime folder itself may be a link.
 */
final class RuntimePaths {

    /** Prefix of the temporary files; a leftover one is harmless and never read. */
    static final String TEMP_PREFIX = ".vectora-";
    /** Suffix of the temporary files. */
    static final String TEMP_SUFFIX = ".tmp";
    private static final int MOVE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<OpenOption> CREATE_NEW_OPTIONS = Set.of(StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private RuntimePaths() {
    }

    /**
     * Resolves {@code relative} below {@code root}.
     *
     * @param root runtime folder
     * @param relative path below it, separated by {@code /}
     * @return the absolute, normalised path
     * @throws TemplateException if the path is not a plain relative path inside {@code root}
     */
    static Path resolve(Path root, String relative) throws TemplateException {
        if (relative.isEmpty()) {
            throw new TemplateException("(empty path)", "empty path");
        }
        Path base = root.toAbsolutePath().normalize();
        Path path = base;
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new TemplateException(printable(relative), "path leaves the runtime folder or is not normalised");
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '\\' || c == ':' || c < ' ' || c == 0x7F) {
                    throw new TemplateException(printable(relative), "path contains a forbidden character");
                }
            }
            try {
                path = path.resolve(part);
            } catch (InvalidPathException e) {
                throw new TemplateException(printable(relative), "path is not valid on this system");
            }
        }
        path = path.normalize();
        if (!path.startsWith(base) || path.equals(base)) {
            throw new TemplateException(printable(relative), "path leaves the runtime folder");
        }
        return path;
    }

    /**
     * Checks that {@code relative} can be written below {@code root} without following a symbolic
     * link, and returns its absolute path. Does not create anything.
     *
     * @param root runtime folder
     * @param relative path below it, separated by {@code /}
     * @return the absolute target path
     * @throws TemplateException if the path is unsafe or a link or file blocks it
     */
    static Path check(Path root, String relative) throws TemplateException {
        Path base = root.toAbsolutePath().normalize();
        Path target = resolve(root, relative);
        Path current = base;
        for (Path part : base.relativize(target)) {
            current = current.resolve(part);
            BasicFileAttributes attributes = attributesOrNull(current);
            if (attributes == null) {
                break;
            }
            if (isLink(attributes)) {
                throw new TemplateException(relative, "refusing to write through the symbolic link or junction "
                        + base.relativize(current).toString().replace('\\', '/'));
            }
            boolean last = current.equals(target);
            if (last ? !attributes.isRegularFile() : !attributes.isDirectory()) {
                throw new TemplateException(relative, (last ? "target is not a regular file: " : "not a folder: ")
                        + base.relativize(current).toString().replace('\\', '/'));
            }
        }
        return target;
    }

    /**
     * Writes {@code content} to {@code relative} below {@code root}, creating missing folders and
     * replacing an existing file.
     *
     * <p>The content never goes into the existing file. It is written to a new, hidden temporary
     * file in the same folder, flushed to disk, and renamed over the target. A crash before the
     * rename leaves the old file as it was; an existing file is never truncated; and a hard link at
     * the target is replaced as a directory entry instead of being written through, so the other
     * names of that file keep their content. For a secret the temporary file is restricted to the
     * owner (POSIX {@code 600}, or an owner-only access list on Windows) before the first byte of
     * the secret goes into it.
     *
     * @param root runtime folder
     * @param relative path below it, separated by {@code /}
     * @param content new file content
     * @param secret whether only the owner may read the file
     * @return the absolute path written
     * @throws TemplateException if the path is unsafe or a link or file blocks it
     * @throws IOException if writing fails
     */
    static Path write(Path root, String relative, byte[] content, boolean secret) throws TemplateException, IOException {
        Path target = check(root, relative);
        Path base = root.toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path current = base;
        for (Path part : base.relativize(target.getParent() == null ? base : target.getParent())) {
            current = current.resolve(part);
            if (part.toString().isEmpty()) {
                continue;
            }
            BasicFileAttributes attributes = attributesOrNull(current);
            if (attributes == null) {
                Files.createDirectory(current);
            } else if (isLink(attributes) || !attributes.isDirectory()) {
                // Appeared after check(); do not follow it.
                throw new TemplateException(relative, "not a folder, or a symbolic link or junction: "
                        + base.relativize(current).toString().replace('\\', '/'));
            }
        }
        Path temp = createTemp(current, secret);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            BasicFileAttributes existing = attributesOrNull(target);
            if (existing != null && (isLink(existing) || !existing.isRegularFile())) {
                // Appeared after check(); never replace a link or a folder.
                throw new TemplateException(relative, "target is not a regular file");
            }
            move(temp, target);
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
        return target;
    }

    /**
     * Creates an empty temporary file next to the target. A secret file gets its owner-only
     * permissions at creation (POSIX) or before anything is written into it (access list).
     */
    private static Path createTemp(Path folder, boolean secret) throws IOException {
        boolean posix = Files.getFileStore(folder).supportsFileAttributeView("posix");
        FileAttribute<?>[] attributes = secret && posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
        for (int attempt = 0; ; attempt++) {
            Path temp = folder.resolve(TEMP_PREFIX + Long.toUnsignedString(RANDOM.nextLong(), 36) + TEMP_SUFFIX);
            try {
                Files.newByteChannel(temp, CREATE_NEW_OPTIONS, attributes).close();
            } catch (FileAlreadyExistsException e) {
                if (attempt >= 10) {
                    throw e;
                }
                continue;
            }
            try {
                if (secret && posix) {
                    // The umask can only remove bits; set the mode explicitly all the same.
                    Files.setPosixFilePermissions(temp, OWNER_ONLY);
                } else if (secret) {
                    restrictAcl(temp);
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            return temp;
        }
    }

    /** Replaces the access list of a file with a single entry: full control for its owner. */
    private static void restrictAcl(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            // Neither POSIX permissions nor access lists (FAT, some network shares): nothing to restrict.
            return;
        }
        acl.setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build()));
    }

    /**
     * Renames the temporary file over the target, atomically where the file system can. Windows
     * refuses a rename for a moment while a virus scanner or an editor holds the target open, so a
     * denied rename is tried a few more times.
     */
    private static void move(Path temp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= MOVE_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Tells whether {@code attributes} (read without following links) belong to a link. A Windows
     * junction is no symbolic link to Java but "other", and may report itself as a folder as well.
     */
    static boolean isLink(BasicFileAttributes attributes) {
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static BasicFileAttributes attributesOrNull(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    private static String printable(String path) {
        StringBuilder out = new StringBuilder(Math.min(path.length(), 120));
        for (int i = 0; i < path.length() && i < 120; i++) {
            char c = path.charAt(i);
            out.append(c < ' ' || c == 0x7F ? '?' : c);
        }
        return out.toString();
    }
}
