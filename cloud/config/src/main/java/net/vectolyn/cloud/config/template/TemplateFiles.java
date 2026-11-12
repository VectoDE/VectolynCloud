// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.template;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.io.RegularPaths;

/**
 * Reads and writes single files of the templates in {@code local/templates/<template>/}, for the
 * template endpoints of the API (the panel's editor).
 *
 * <p>Only paths that pass {@link TemplatePaths#isSafePath} and do not
 * {@linkplain TemplatePaths#looksSecret look like a secret} are listed, read or written, the same
 * rules as the cluster mirror. Links, junctions and special files are never followed: a template
 * folder, a folder on the way or the file itself that is one fails with a
 * {@link FileSystemException}. A write replaces the file atomically ({@link AtomicFileWriter}) and
 * is checked against an ETag ({@code If-Match}) under one lock, so two editors cannot overwrite
 * each other unnoticed.
 */
public final class TemplateFiles {

    /** Largest file {@link #file} hands out. */
    public static final int MAX_READ_BYTES = 1024 * 1024;
    /**
     * Largest file {@link #write} accepts. The API reads strings of at most 64 KiB, and 48 KiB in
     * Base64 is exactly that.
     */
    public static final int MAX_WRITE_BYTES = 48 * 1024;
    /** Most files {@link #files} lists, the limit of the cluster mirror. */
    public static final int MAX_LISTED_FILES = 20_000;
    /** Most templates {@link #templates} lists, the limit of the cluster mirror. */
    public static final int MAX_TEMPLATES = 256;

    /**
     * One file of a template.
     *
     * @param path relative to the template folder, with {@code /}
     * @param size size in bytes
     */
    public record FileInfo(String path, long size) {
    }

    /**
     * The files of a template.
     *
     * @param files     the files in path order, at most {@link #MAX_LISTED_FILES}
     * @param truncated whether more files exist than were listed
     */
    public record Listing(List<FileInfo> files, boolean truncated) {
    }

    /** What {@link #write} did. */
    public enum Outcome {
        /** The file was new. */
        CREATED,
        /** An existing file was replaced. */
        REPLACED
    }

    /** {@code If-Match} did not fit, or was missing for an existing file. */
    public static final class PreconditionException extends Exception {

        private static final long serialVersionUID = 1L;

        private final boolean required;

        PreconditionException(boolean required, String message) {
            super(message);
            this.required = required;
        }

        /**
         * Whether the condition was missing (HTTP 428) rather than wrong (HTTP 412).
         *
         * @return whether it was missing
         */
        public boolean required() {
            return required;
        }
    }

    /** A file is larger than the limit of the operation. */
    public static final class TooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        private final long limit;

        TooLargeException(long limit) {
            super("the file is larger than " + limit + " bytes");
            this.limit = limit;
        }

        /**
         * The limit that was exceeded.
         *
         * @return the limit in bytes
         */
        public long limit() {
            return limit;
        }
    }

    private final Path templatesDir;
    private final AtomicFileWriter writer;
    private final Object lock = new Object();

    /**
     * Creates the access for a cloud home.
     *
     * @param home the home
     */
    public TemplateFiles(CloudHome home) {
        this(home.templatesDir(), new AtomicFileWriter());
    }

    /**
     * Creates the access for a templates folder.
     *
     * @param templatesDir {@code local/templates}
     * @param writer       the writer
     */
    public TemplateFiles(Path templatesDir, AtomicFileWriter writer) {
        this.templatesDir = Objects.requireNonNull(templatesDir, "templatesDir");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * The template folders with a valid name, sorted. A link or special file in their place is
     * left out.
     *
     * @return the names, at most {@link #MAX_TEMPLATES}
     * @throws IOException if {@code local/templates} cannot be read
     */
    public List<String> templates() throws IOException {
        if (RegularPaths.kind(templatesDir) != RegularPaths.Kind.DIRECTORY) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(templatesDir)) {
            for (Path folder : stream) {
                String name = folder.getFileName().toString();
                if (TemplatePaths.isTemplateName(name) && RegularPaths.kind(folder) == RegularPaths.Kind.DIRECTORY) {
                    names.add(name);
                }
            }
        }
        names.sort(null);
        return List.copyOf(names.subList(0, Math.min(names.size(), MAX_TEMPLATES)));
    }

    /**
     * The files of a template that the API hands out: safe paths, no secrets, no links, no
     * special files, no secret folders. Unlike the mirror, an unreadable folder does not stop the
     * listing.
     *
     * @param template the template name
     * @return the files
     * @throws IllegalArgumentException if the name is no template name
     * @throws NoSuchFileException      if the template does not exist
     * @throws IOException              if it cannot be read
     */
    public Listing files(String template) throws IOException {
        Path root = template(template);
        List<FileInfo> files = new ArrayList<>();
        boolean[] truncated = {false};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), TemplatePaths.MAX_DEPTH + 1,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relative = relative(root, dir);
                        if (TemplatePaths.isSecretFolder(dir.getFileName().toString()) || !attributes.isDirectory()
                                || attributes.isSymbolicLink() || attributes.isOther()
                                || !TemplatePaths.isSafePath(relative)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        String relative = relative(root, file);
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                                || !TemplatePaths.isSafePath(relative) || TemplatePaths.looksSecret(relative)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (files.size() >= MAX_LISTED_FILES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        files.add(new FileInfo(relative, attributes.size()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        files.sort((a, b) -> a.path().compareTo(b.path()));
        return new Listing(List.copyOf(files), truncated[0]);
    }

    /**
     * The content of one file.
     *
     * @param template the template name
     * @param path     the path inside the template
     * @return the bytes
     * @throws IllegalArgumentException if the name or path breaks the rules or looks like a secret
     * @throws NoSuchFileException      if the template or the file does not exist
     * @throws TooLargeException        if the file is larger than {@link #MAX_READ_BYTES}
     * @throws FileSystemException      if a link, junction or special file is in the way
     * @throws IOException              if it cannot be read
     */
    public byte[] file(String template, String path) throws IOException {
        Path root = template(template);
        Path target = resolve(root, path);
        synchronized (lock) {
            return readCurrent(root, target).orElseThrow(() -> new NoSuchFileException(path));
        }
    }

    /**
     * Creates or replaces one file. {@code ifMatch} decides whether the write may happen, checked
     * under the same lock as the write:
     * <ul>
     *   <li>{@code null}: only for a new file; an existing one needs the condition (428).</li>
     *   <li>{@code *}: only for an existing file.</li>
     *   <li>an ETag ({@link #etag}): only if the file has exactly that content now.</li>
     * </ul>
     * Folders on the way are created; the template folder itself must exist.
     *
     * @param template the template name
     * @param path     the path inside the template
     * @param content  the new content, at most {@link #MAX_WRITE_BYTES}
     * @param ifMatch  the condition, or null
     * @return whether the file was created or replaced
     * @throws IllegalArgumentException if the name or path breaks the rules or looks like a secret
     * @throws NoSuchFileException      if the template does not exist
     * @throws TooLargeException        if the content is larger than {@link #MAX_WRITE_BYTES}
     * @throws PreconditionException    if {@code ifMatch} does not fit
     * @throws FileSystemException      if a link, junction, file or special file is in the way
     * @throws IOException              if it cannot be written
     */
    public Outcome write(String template, String path, byte[] content, String ifMatch)
            throws IOException, PreconditionException {
        Objects.requireNonNull(content, "content");
        if (content.length > MAX_WRITE_BYTES) {
            throw new TooLargeException(MAX_WRITE_BYTES);
        }
        Path root = template(template);
        Path target = resolve(root, path);
        synchronized (lock) {
            Optional<byte[]> current = readCurrent(root, target);
            if (ifMatch == null) {
                if (current.isPresent()) {
                    throw new PreconditionException(true, "The file exists; send If-Match with its ETag to replace it.");
                }
            } else if (current.isEmpty() || !(ifMatch.equals("*") || ifMatch.equals(etag(current.get())))) {
                throw new PreconditionException(false, current.isEmpty()
                        ? "The file does not exist; leave If-Match out to create it."
                        : "The file changed since it was read (If-Match does not match its ETag).");
            }
            if (current.isPresent() && Arrays.equals(current.get(), content)) {
                return Outcome.REPLACED;
            }
            // Folders only after the condition passed, so a refused write leaves nothing behind.
            prepareFolders(root, target.getParent());
            writer.write(target, content);
            return current.isPresent() ? Outcome.REPLACED : Outcome.CREATED;
        }
    }

    /**
     * The ETag of a file's content: the first 16 bytes of its SHA-256 in hex, quoted, the same
     * form as the ETags of the configuration documents.
     *
     * @param content the content
     * @return the ETag
     */
    public static String etag(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /** The folder of a template; it must exist as a real folder. */
    private Path template(String template) throws IOException {
        if (!TemplatePaths.isTemplateName(template)) {
            throw new IllegalArgumentException("not a template name");
        }
        RegularPaths.Kind dir = RegularPaths.kind(templatesDir);
        if (dir == RegularPaths.Kind.MISSING) {
            throw new NoSuchFileException(template);
        }
        if (dir != RegularPaths.Kind.DIRECTORY) {
            throw new FileSystemException(CloudHome.TEMPLATES_DIR, null, "must be a regular folder");
        }
        Path root = templatesDir.resolve(template);
        RegularPaths.Kind kind = RegularPaths.kind(root);
        if (kind == RegularPaths.Kind.MISSING) {
            throw new NoSuchFileException(template);
        }
        if (kind != RegularPaths.Kind.DIRECTORY) {
            throw new FileSystemException(template, null, "must be a regular folder");
        }
        return root;
    }

    /** The file of a checked path; every segment checked again, nothing resolved from outside. */
    private static Path resolve(Path root, String path) {
        if (!TemplatePaths.isSafePath(path) || TemplatePaths.looksSecret(path)) {
            throw new IllegalArgumentException("not a path the cloud hands out");
        }
        Path target = root;
        for (String segment : path.split("/", -1)) {
            target = target.resolve(segment);
        }
        if (!target.normalize().startsWith(root.normalize()) || target.normalize().equals(root.normalize())) {
            throw new IllegalArgumentException("not a path inside the template");
        }
        return target;
    }

    /** Every folder between the template and {@code dir} must be a real folder or missing (then created). */
    private static void prepareFolders(Path root, Path dir) throws IOException {
        List<Path> chain = new ArrayList<>();
        for (Path p = dir; !p.equals(root); p = p.getParent()) {
            chain.add(0, p);
        }
        for (Path p : chain) {
            RegularPaths.Kind kind = RegularPaths.kind(p);
            if (kind == RegularPaths.Kind.MISSING) {
                Files.createDirectory(p);
            } else if (kind != RegularPaths.Kind.DIRECTORY) {
                throw new FileSystemException(root.relativize(p).toString().replace('\\', '/'), null,
                        "must be a regular folder, not a file, link or junction");
            }
        }
    }

    /**
     * The content of the file, empty if it or a folder on the way is missing. Every folder from the
     * template down is checked without following links, since {@link LinkOption#NOFOLLOW_LINKS}
     * only covers the last segment of a path.
     */
    private static Optional<byte[]> readCurrent(Path root, Path target) throws IOException {
        List<Path> chain = new ArrayList<>();
        for (Path p = target.getParent(); !p.equals(root); p = p.getParent()) {
            chain.add(0, p);
        }
        for (Path p : chain) {
            RegularPaths.Kind kind = RegularPaths.kind(p);
            if (kind == RegularPaths.Kind.MISSING) {
                return Optional.empty();
            }
            if (kind != RegularPaths.Kind.DIRECTORY) {
                throw new FileSystemException(relative(root, p), null,
                        "must be a regular folder, not a file, link or junction");
            }
        }
        RegularPaths.Kind kind = RegularPaths.kind(target);
        if (kind == RegularPaths.Kind.MISSING) {
            return Optional.empty();
        }
        if (kind != RegularPaths.Kind.FILE) {
            throw new FileSystemException(relative(root, target), null,
                    "must be a regular file, not a folder, link or junction");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(target,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size > MAX_READ_BYTES) {
                throw new TooLargeException(MAX_READ_BYTES);
            }
            // One byte more than announced, to notice a file that grows while it is read.
            ByteBuffer buffer = ByteBuffer.allocate((int) size + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // read until the end or one byte past the size
            }
            if (buffer.position() > size) {
                throw new IOException("the file changed while it was read");
            }
            return Optional.of(Arrays.copyOf(buffer.array(), buffer.position()));
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
