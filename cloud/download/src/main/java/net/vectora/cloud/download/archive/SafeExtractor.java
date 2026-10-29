package net.vectora.cloud.download.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Unpacks zip and tar.gz archives into a new, empty directory without letting the archive
 * write anywhere else.
 *
 * <ul>
 *   <li>Entry names must be relative, use {@code /} as separator and contain no {@code ..},
 *       no {@code \}, no {@code :} and no NUL (zip slip).</li>
 *   <li>Symbolic links (TAR only) are created after all files, so no file is ever written through
 *       a link. A link target must be relative and must stay inside the directory, checked both
 *       on the path and, after creation, on the real path. Hard links may only point to a file
 *       extracted earlier and are written as copies.</li>
 *   <li>Zip entries are always written as plain files; a zip cannot create links here.</li>
 *   <li>{@link ExtractionLimits} caps entries, bytes per file and bytes in total, counted while
 *       writing (zip bombs).</li>
 *   <li>The target directory must not exist or be empty. If extraction fails, everything written
 *       so far is deleted again.</li>
 *   <li>TAR permission bits are applied on POSIX file systems, without setuid, setgid and sticky.</li>
 * </ul>
 */
public final class SafeExtractor {

    private final ExtractionLimits limits;

    /**
     * Creates the extractor.
     *
     * @param limits the limits
     */
    public SafeExtractor(ExtractionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * What an extraction produced.
     *
     * @param files regular files written (hard links included)
     * @param directories directories created
     * @param symlinks symbolic links created
     * @param bytes bytes written
     */
    public record Result(int files, int directories, int symlinks, long bytes) {
    }

    /**
     * Unpacks a zip file.
     *
     * @param archive the zip file
     * @param destination a directory that does not exist or is empty
     * @return what was written
     * @throws ArchiveException if the archive breaks a rule; nothing is left behind
     * @throws IOException if reading or writing fails
     */
    public Result extractZip(Path archive, Path destination) throws IOException {
        Session session = new Session(destination);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    session.directory(entry.getName(), -1);
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        session.file(entry.getName(), in, -1);
                    }
                }
            }
            return session.finish();
        } catch (ZipException e) {
            session.abort();
            throw new ArchiveException("broken zip file " + archive.getFileName() + ": " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            session.abort();
            throw e;
        }
    }

    /**
     * Unpacks a gzip-compressed TAR file.
     *
     * @param archive the .tar.gz file
     * @param destination a directory that does not exist or is empty
     * @return what was written
     * @throws ArchiveException if the archive breaks a rule; nothing is left behind
     * @throws IOException if reading or writing fails
     */
    public Result extractTarGz(Path archive, Path destination) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
                InputStream gzip = new GZIPInputStream(new BufferedInputStream(raw), 64 * 1024)) {
            return extractTar(gzip, destination);
        } catch (ZipException e) {
            throw new ArchiveException("broken gzip stream in " + archive.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Unpacks an uncompressed TAR stream.
     *
     * @param tar the TAR stream; not closed
     * @param destination a directory that does not exist or is empty
     * @return what was written
     * @throws ArchiveException if the archive breaks a rule; nothing is left behind
     * @throws IOException if reading or writing fails
     */
    public Result extractTar(InputStream tar, Path destination) throws IOException {
        Session session = new Session(destination);
        try {
            TarReader reader = new TarReader(tar);
            TarEntry entry;
            while ((entry = reader.next()) != null) {
                switch (entry.type()) {
                    case FILE -> session.file(entry.name(), reader.content(), entry.mode());
                    case DIRECTORY -> session.directory(entry.name(), entry.mode());
                    case SYMLINK -> session.symlink(entry.name(), entry.linkName());
                    case HARDLINK -> session.hardlink(entry.name(), entry.linkName(), entry.mode());
                }
            }
            return session.finish();
        } catch (IOException | RuntimeException e) {
            session.abort();
            throw e;
        }
    }

    /** State of one extraction. */
    private final class Session {

        private final Path destination;
        private final Path root;
        private final boolean created;
        private final boolean posix;
        private final Set<Path> files = new HashSet<>();
        private final Map<Path, String> symlinks = new LinkedHashMap<>();
        private final Map<Path, Integer> directoryModes = new LinkedHashMap<>();
        private int entries;
        private int fileCount;
        private int directoryCount;
        private long total;

        Session(Path destination) throws IOException {
            this.destination = destination.toAbsolutePath().normalize();
            boolean exists = Files.exists(this.destination, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                if (!Files.isDirectory(this.destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ArchiveException(destination + " exists and is not a directory");
                }
                try (DirectoryStream<Path> content = Files.newDirectoryStream(this.destination)) {
                    if (content.iterator().hasNext()) {
                        throw new ArchiveException(destination + " is not empty");
                    }
                }
            } else {
                Files.createDirectories(this.destination);
            }
            this.created = !exists;
            this.root = this.destination.toRealPath();
            this.posix = Files.getFileAttributeView(root, PosixFileAttributeView.class) != null;
        }

        void file(String name, InputStream content, int mode) throws IOException {
            Path target = resolve(name);
            count();
            createParents(target);
            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                int n;
                while ((n = content.read(buffer)) >= 0) {
                    written += n;
                    total += n;
                    if (written > limits.maxEntryBytes()) {
                        throw new ArchiveException(name + " is larger than " + limits.maxEntryBytes() + " bytes");
                    }
                    if (total > limits.maxTotalBytes()) {
                        throw new ArchiveException("archive unpacks to more than " + limits.maxTotalBytes() + " bytes");
                    }
                    out.write(buffer, 0, n);
                }
            } catch (FileAlreadyExistsException e) {
                throw new ArchiveException("duplicate or conflicting entry " + name, e);
            }
            files.add(target);
            fileCount++;
            applyMode(target, mode);
        }

        void directory(String name, int mode) throws IOException {
            Path target = resolve(name);
            count();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArchiveException("directory entry " + name + " conflicts with a file");
            }
            createParents(target.resolve("x"));
            if (directoryModes.putIfAbsent(target, mode) == null) {
                directoryCount++;
            }
        }

        void symlink(String name, String linkTarget) throws IOException {
            Path link = resolve(name);
            count();
            if (linkTarget.startsWith("/") || linkTarget.contains("\\") || linkTarget.contains(":")
                    || linkTarget.indexOf('\0') >= 0) {
                throw new ArchiveException("symlink " + name + " has an absolute or unsafe target " + linkTarget);
            }
            Path resolved = link.getParent().resolve(linkTarget).normalize();
            if (!resolved.startsWith(root)) {
                throw new ArchiveException("symlink " + name + " points outside the target directory: " + linkTarget);
            }
            if (symlinks.putIfAbsent(link, linkTarget) != null) {
                throw new ArchiveException("duplicate entry " + name);
            }
        }

        void hardlink(String name, String linkTarget, int mode) throws IOException {
            Path target = resolve(linkTarget);
            if (!files.contains(target)) {
                throw new ArchiveException("hard link " + name + " must point to a file extracted before it: " + linkTarget);
            }
            try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                file(name, in, mode);
            }
        }

        Result finish() throws IOException {
            for (Map.Entry<Path, String> entry : symlinks.entrySet()) {
                Path link = entry.getKey();
                createParents(link);
                try {
                    Files.createSymbolicLink(link, link.getFileSystem().getPath(entry.getValue()));
                } catch (FileAlreadyExistsException e) {
                    throw new ArchiveException("symlink " + root.relativize(link) + " conflicts with another entry", e);
                } catch (UnsupportedOperationException | FileSystemException e) {
                    throw new ArchiveException("cannot create symlink " + root.relativize(link) + " here: "
                            + e.getMessage(), e);
                }
                // Check each link right away, before a later link or its parent directories are
                // created through it.
                if (!realTargetInside(link)) {
                    throw new ArchiveException("symlink " + root.relativize(link) + " resolves outside the target directory");
                }
            }
            // Second pass: with every link in place, the real target must still be inside.
            for (Path link : symlinks.keySet()) {
                if (!realTargetInside(link)) {
                    throw new ArchiveException("symlink " + root.relativize(link) + " resolves outside the target directory");
                }
            }
            for (Map.Entry<Path, Integer> entry : directoryModes.entrySet()) {
                applyMode(entry.getKey(), entry.getValue());
            }
            return new Result(fileCount, directoryCount, symlinks.size(), total);
        }

        void abort() {
            try {
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                List<Path> paths;
                try (Stream<Path> walk = Files.walk(destination)) {
                    paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
                }
                for (Path path : paths) {
                    if (!created && path.equals(destination)) {
                        continue;
                    }
                    makeWritable(path);
                    Files.deleteIfExists(path);
                }
            } catch (IOException | RuntimeException ignored) {
                // Best effort; the original error is what matters.
            }
        }

        private void makeWritable(Path path) {
            if (posix && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Deleting may still work.
                }
            }
        }

        private boolean realTargetInside(Path link) throws IOException {
            Path target = link.getParent().resolve(Files.readSymbolicLink(link));
            // Resolve the longest existing prefix for real; the rest cannot contain links.
            Path existing = target;
            List<Path> rest = new ArrayList<>();
            while (existing != null && !Files.exists(existing)) {
                rest.add(0, existing.getFileName());
                existing = existing.getParent();
            }
            if (existing == null) {
                return false;
            }
            // A ".." after a missing part would be resolved by the kernel only once that part
            // exists, possibly as a link; refuse instead of guessing.
            for (Path part : rest) {
                if (part.toString().equals("..")) {
                    return false;
                }
            }
            Path real = existing.toRealPath();
            for (Path part : rest) {
                real = real.resolve(part);
            }
            return real.normalize().startsWith(root);
        }

        private void count() throws ArchiveException {
            if (++entries > limits.maxEntries()) {
                throw new ArchiveException("archive has more than " + limits.maxEntries() + " entries");
            }
        }

        private void createParents(Path target) throws IOException {
            Path parent = target.getParent();
            // createDirectories follows links, so check the deepest existing ancestor before
            // creating anything below it: a link there may already point outside.
            Path existing = parent;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(root)) {
                throw new ArchiveException("entry escapes the target directory: " + root.relativize(target));
            }
            Files.createDirectories(parent);
            if (!parent.toRealPath().startsWith(root)) {
                throw new ArchiveException("entry escapes the target directory: " + root.relativize(target));
            }
        }

        private Path resolve(String name) throws ArchiveException {
            if (name.isEmpty() || name.indexOf('\0') >= 0 || name.contains("\\") || name.contains(":")
                    || name.startsWith("/")) {
                throw new ArchiveException("unsafe entry name: " + name);
            }
            List<String> parts = new ArrayList<>();
            for (String part : name.split("/")) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    throw new ArchiveException("entry name with '..': " + name);
                }
                parts.add(part);
            }
            if (parts.isEmpty()) {
                throw new ArchiveException("entry name without a path: " + name);
            }
            Path target;
            try {
                target = root.resolve(String.join("/", parts)).normalize();
            } catch (java.nio.file.InvalidPathException e) {
                throw new ArchiveException("entry name not valid on this system: " + name, e);
            }
            if (!target.startsWith(root) || target.equals(root)) {
                throw new ArchiveException("entry escapes the target directory: " + name);
            }
            return target;
        }

        private void applyMode(Path path, int mode) throws IOException {
            if (!posix || mode < 0) {
                return;
            }
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            PosixFilePermission[] order = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
            };
            for (int bit = 0; bit < order.length; bit++) {
                if ((mode & (1 << bit)) != 0) {
                    permissions.add(order[bit]);
                }
            }
            // The cloud must be able to read and later delete what it unpacked.
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
