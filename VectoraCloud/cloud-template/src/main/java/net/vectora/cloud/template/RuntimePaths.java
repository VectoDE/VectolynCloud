package net.vectora.cloud.template;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * Safe writes below a runtime folder.
 *
 * <p>Relative paths use {@code /} as separator and must stay inside the runtime folder: no
 * {@code ..}, no {@code .}, no empty parts, no absolute paths, no drive letters, no backslashes, no
 * control characters. Symbolic links below the runtime folder are never followed: a link where a
 * folder or the target file should be is an error. The runtime folder itself may be a link.
 */
final class RuntimePaths {

    private static final Set<OpenOption> WRITE_OPTIONS = Set.of(StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
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
            if (attributes.isSymbolicLink()) {
                throw new TemplateException(relative, "refusing to write through the symbolic link "
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
     * @param root runtime folder
     * @param relative path below it, separated by {@code /}
     * @param content new file content
     * @param secret whether only the owner may read the file (applied where POSIX permissions exist)
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
            } else if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                // Appeared after check(); do not follow it.
                throw new TemplateException(relative, "not a folder or a symbolic link: "
                        + base.relativize(current).toString().replace('\\', '/'));
            }
        }
        boolean posix = secret && Files.getFileStore(current).supportsFileAttributeView("posix");
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
        try (SeekableByteChannel channel = Files.newByteChannel(target, WRITE_OPTIONS, attributes);
             OutputStream out = Channels.newOutputStream(channel)) {
            out.write(content);
        }
        if (posix) {
            // The attribute applies only to new files; an existing one keeps its old mode otherwise.
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        }
        return target;
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
