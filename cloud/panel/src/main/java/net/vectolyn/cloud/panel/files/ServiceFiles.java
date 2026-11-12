// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.files;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The files of the services of this node for the panel's file manager: the folder of a running
 * service ({@code temp/services/<id>}) or of a static service ({@code local/services/<id>}).
 *
 * <p>Paths are relative, separated by {@code /}, and must stay inside the service folder: no
 * {@code ..}, no {@code .}, no empty parts, no absolute paths, no drive letters, no backslashes, no
 * control characters, no {@code :} and at most 512 characters. Symbolic links and other special
 * entries below the service folder are never followed or opened: a link where a folder or file is
 * expected is refused. Files are replaced atomically (written next to the target, then moved).
 */
public final class ServiceFiles {

    /** Largest file the panel reads into the editor or lets download. */
    public static final long MAX_READ_BYTES = 8L * 1024 * 1024;
    /** Most entries one listing returns. */
    public static final int MAX_LIST = 2000;

    private static final Pattern SERVICE_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
    private static final int MAX_PATH = 512;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path home;

    /** A request breaks a rule; the message is meant for the user. */
    public static final class FileException extends Exception {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        FileException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        /** @return the HTTP status to answer */
        public int status() {
            return status;
        }

        /** @return a stable code */
        public String code() {
            return code;
        }
    }

    /**
     * One entry of a folder.
     *
     * @param name     the name
     * @param folder   whether it is a folder
     * @param size     bytes, 0 for folders
     * @param modified last change, ISO-8601
     */
    public record Entry(String name, boolean folder, long size, String modified) {
    }

    /**
     * Creates the access.
     *
     * @param home the cloud home
     */
    public ServiceFiles(Path home) {
        this.home = home;
    }

    /**
     * Checks a relative path.
     *
     * @param text the path as sent, already percent-decoded once; empty for the service folder
     * @return the parts
     * @throws FileException if it breaks a rule
     */
    public static List<String> parsePath(String text) throws FileException {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() > MAX_PATH || text.startsWith("/") || text.endsWith("/")) {
            throw invalidPath();
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 255) {
                throw invalidPath();
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0) {
                    throw invalidPath();
                }
            }
            // Names Windows treats specially: trailing dot or space, and device names.
            if (part.endsWith(".") || part.endsWith(" ") || WINDOWS_DEVICE.matcher(part).matches()) {
                throw invalidPath();
            }
            parts.add(part);
        }
        return List.copyOf(parts);
    }

    private static FileException invalidPath() {
        return new FileException(400, "invalid-path", "The path breaks a rule: relative, with / between the parts, no . or .. and no \\ or :.");
    }

    /**
     * The folder of a service.
     *
     * @param serviceId the service id, for example {@code lobby-1}
     * @return the folder
     * @throws FileException 400 for an invalid id, 404 if the service has no folder on this node
     */
    public Path serviceRoot(String serviceId) throws FileException {
        if (serviceId == null || !SERVICE_ID.matcher(serviceId).matches()) {
            throw new FileException(400, "invalid-service", "No valid service id.");
        }
        for (String base : List.of("temp/services", "local/services")) {
            Path dir = home.resolve(base).resolve(serviceId);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        throw new FileException(404, "no-such-service", "This service has no folder on this node.");
    }

    /**
     * Resolves a path below the service folder without following any link below it.
     *
     * @param root  the service folder
     * @param parts the checked parts
     * @return the path; it may not exist
     * @throws FileException if a part below the root is a link or another special entry
     * @throws IOException   if the file system cannot be read
     */
    static Path resolve(Path root, List<String> parts) throws FileException, IOException {
        Path current = root;
        for (String part : parts) {
            current = current.resolve(part);
            if (!current.normalize().startsWith(root.normalize())) {
                throw invalidPath();
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new FileException(403, "link-refused", "Links are not followed in the file manager.");
                }
            }
        }
        return current;
    }

    /**
     * Lists a folder, folders first, then by name.
     *
     * @param serviceId the service
     * @param path      the folder below it
     * @return the entries
     * @throws FileException if the path breaks a rule or is not a folder
     * @throws IOException   if the folder cannot be read
     */
    public List<Entry> list(String serviceId, String path) throws FileException, IOException {
        Path root = serviceRoot(serviceId);
        Path dir = resolve(root, parsePath(path));
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileException(404, "no-such-folder", "No such folder.");
        }
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (entries.size() >= MAX_LIST) {
                    break;
                }
                BasicFileAttributes a = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || a.isOther()) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (name.startsWith(".vectolyn-") && name.endsWith(".tmp")) {
                    continue;
                }
                entries.add(new Entry(name, a.isDirectory(), a.isDirectory() ? 0 : a.size(),
                        a.lastModifiedTime().toInstant().toString()));
            }
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.folder()).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * Reads a file.
     *
     * @param serviceId the service
     * @param path      the file
     * @return the content
     * @throws FileException if it is missing, a folder or larger than {@value #MAX_READ_BYTES} bytes
     * @throws IOException   if it cannot be read
     */
    public byte[] read(String serviceId, String path) throws FileException, IOException {
        Path file = file(serviceId, path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileException(404, "no-such-file", "No such file.");
        }
        if (Files.size(file) > MAX_READ_BYTES) {
            throw new FileException(413, "file-too-large", "The file is larger than 8 MiB; copy it from the node directly.");
        }
        return Files.readAllBytes(file);
    }

    /**
     * Writes a file, creating missing folders.
     *
     * @param serviceId the service
     * @param path      the file
     * @param content   the new content
     * @throws FileException if the path breaks a rule or is a folder
     * @throws IOException   if it cannot be written
     */
    public void write(String serviceId, String path, byte[] content) throws FileException, IOException {
        Path root = serviceRoot(serviceId);
        List<String> parts = parsePath(path);
        if (parts.isEmpty()) {
            throw invalidPath();
        }
        Path file = resolve(root, parts);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileException(409, "is-folder", "A folder has this name.");
        }
        Path parent = file.getParent();
        createFolders(root, parts.subList(0, parts.size() - 1));
        Path temp = parent.resolve(".vectolyn-" + HexFormat.of().formatHex(randomBytes()) + ".tmp");
        try {
            Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Creates a folder and its missing parents.
     *
     * @param serviceId the service
     * @param path      the folder
     * @throws FileException if the path breaks a rule or a file has the name
     * @throws IOException   if it cannot be created
     */
    public void createFolder(String serviceId, String path) throws FileException, IOException {
        Path root = serviceRoot(serviceId);
        List<String> parts = parsePath(path);
        if (parts.isEmpty()) {
            throw invalidPath();
        }
        createFolders(root, parts);
    }

    private static void createFolders(Path root, List<String> parts) throws FileException, IOException {
        Path current = root;
        for (String part : parts) {
            current = resolve(current, List.of(part));
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException e) {
                    // created at the same time; checked below
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileException(409, "is-file", "A file has the name of a folder in the path.");
            }
        }
    }

    /**
     * Deletes a file or an empty folder.
     *
     * @param serviceId the service
     * @param path      the entry
     * @throws FileException if it is missing or a folder that is not empty
     * @throws IOException   if it cannot be deleted
     */
    public void delete(String serviceId, String path) throws FileException, IOException {
        Path entry = file(serviceId, path);
        try {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(entry)) {
                    if (stream.iterator().hasNext()) {
                        throw new FileException(409, "folder-not-empty", "Only empty folders can be deleted.");
                    }
                }
            }
            Files.delete(entry);
        } catch (NoSuchFileException e) {
            throw new FileException(404, "no-such-file", "No such file.");
        }
    }

    private Path file(String serviceId, String path) throws FileException, IOException {
        List<String> parts = parsePath(path);
        if (parts.isEmpty()) {
            throw invalidPath();
        }
        return resolve(serviceRoot(serviceId), parts);
    }

    /**
     * The name of the last part, for a download.
     *
     * @param path the path
     * @return the file name
     */
    public static Optional<String> fileName(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(path.substring(path.lastIndexOf('/') + 1));
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
