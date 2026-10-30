package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.vectolyn.cloud.cluster.MirrorMessages.ChunkRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.Manifest;
import net.vectolyn.cloud.cluster.MirrorMessages.ManifestRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateList;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.protocol.PacketReader;

/**
 * The head's side of the template sync: every folder directly under {@code local/templates/} is a
 * template. A scan walks each without following links and offers only regular files with a safe
 * path ({@link MirrorPaths#isSafePath}); links, junctions and special files, files that look like
 * secrets ({@link MirrorPaths#looksSecret}) and anything in a {@code secrets} folder are left out.
 * A template above a limit is not offered at all. SHA-256s are cached by size and modification
 * time, so a rescan reads only files that changed.
 *
 * <p>A chunk is read only for a path of the current manifest, and only after checking again that
 * every part of it is a real folder or file inside the template and no link.
 */
final class TemplateSource {

    private static final System.Logger LOG = System.getLogger(TemplateSource.class.getName());

    /**
     * One template as the members see it.
     *
     * @param name    its folder name
     * @param root    the folder
     * @param digest  digest of its files
     * @param entries its files, sorted by path
     * @param bytes   their total size
     * @param byPath  the same files by path
     */
    record Template(String name, Path root, byte[] digest, List<FileEntry> entries, long bytes,
                    Map<String, FileEntry> byPath) {

        TemplateEntry entry() {
            return new TemplateEntry(name, digest, entries.size(), bytes);
        }

        FileEntry find(String path) {
            return byPath.get(path);
        }
    }

    private record Cached(long size, FileTime modified, byte[] sha256) {
    }

    private final Path templatesDir;
    private final Map<Path, Cached> hashes = new HashMap<>();
    private volatile Map<String, Template> catalog = Map.of();

    /**
     * Creates the source; nothing is offered until {@link #rescan()}.
     *
     * @param templatesDir {@code local/templates}
     */
    TemplateSource(Path templatesDir) {
        this.templatesDir = Objects.requireNonNull(templatesDir, "templatesDir");
    }

    /** The templates offered now. */
    Map<String, Template> catalog() {
        return catalog;
    }

    /**
     * Scans {@code local/templates/} again.
     *
     * @return whether any template was added, removed or changed
     */
    synchronized boolean rescan() {
        Map<String, Template> next = new TreeMap<>();
        Set<Path> seen = new HashSet<>();
        long total = 0;
        try {
            if (RegularPaths.kind(templatesDir) != RegularPaths.Kind.DIRECTORY) {
                return replace(next, seen);
            }
            List<Path> folders = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(templatesDir)) {
                stream.forEach(folders::add);
            }
            folders.sort(null);
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (RegularPaths.kind(folder) != RegularPaths.Kind.DIRECTORY) {
                    continue;
                }
                if (!MirrorPaths.isTemplateName(name)) {
                    LOG.log(System.Logger.Level.WARNING, "Cluster: template folder {0} has no valid name; not synced",
                            MirrorPaths.isSafePath(name) ? name : "?");
                    continue;
                }
                if (next.size() >= MirrorPaths.MAX_TEMPLATES) {
                    LOG.log(System.Logger.Level.WARNING, "Cluster: more than {0} templates; {1} and the rest are not "
                            + "synced", MirrorPaths.MAX_TEMPLATES, name);
                    break;
                }
                Template template = scan(name, folder, seen);
                if (template == null) {
                    continue;
                }
                if (total + template.bytes() > MirrorPaths.MAX_TEMPLATES_BYTES) {
                    LOG.log(System.Logger.Level.WARNING, "Cluster: the templates are larger than {0} bytes together; "
                            + "{1} is not synced", MirrorPaths.MAX_TEMPLATES_BYTES, name);
                    continue;
                }
                total += template.bytes();
                next.put(name, template);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: local/templates cannot be read ({0}); the templates offered "
                    + "stay as they were", e.getMessage());
            return false;
        }
        return replace(next, seen);
    }

    private boolean replace(Map<String, Template> next, Set<Path> seen) {
        hashes.keySet().retainAll(seen);
        Map<String, Template> before = catalog;
        boolean changed = !before.keySet().equals(next.keySet());
        for (Map.Entry<String, Template> entry : next.entrySet()) {
            Template old = before.get(entry.getKey());
            changed |= old == null || !Arrays.equals(old.digest(), entry.getValue().digest());
        }
        catalog = Map.copyOf(next);
        return changed;
    }

    /** One template, or null if it breaks a limit. */
    private Template scan(String name, Path root, Set<Path> seen) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        long[] bytes = {0};
        int[] skipped = {0};
        boolean[] over = {false};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MirrorPaths.MAX_DEPTH + 1,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relative = relative(root, dir);
                        boolean secrets = dir.getFileName().toString().toLowerCase(Locale.ROOT).equals("secrets");
                        if (secrets || !attributes.isDirectory() || attributes.isSymbolicLink()
                                || !MirrorPaths.isSafePath(relative)) {
                            skipped[0]++;
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        String relative = relative(root, file);
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                                || !MirrorPaths.isSafePath(relative) || MirrorPaths.looksSecret(relative)) {
                            skipped[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        if (attributes.size() > MirrorPaths.MAX_TEMPLATE_FILE_BYTES
                                || entries.size() >= MirrorPaths.MAX_TEMPLATE_FILES) {
                            over[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        Path absolute = file.toAbsolutePath();
                        seen.add(absolute);
                        byte[] sha = hash(absolute, attributes);
                        if (sha == null) {
                            skipped[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        entries.add(new FileEntry(relative, attributes.size(), sha));
                        bytes[0] += attributes.size();
                        if (bytes[0] > MirrorPaths.MAX_TEMPLATE_BYTES) {
                            over[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
        if (over[0]) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: template {0} is above a limit (at most {1} files, {2} bytes "
                    + "per file, {3} bytes in total); it is not synced", name, MirrorPaths.MAX_TEMPLATE_FILES,
                    MirrorPaths.MAX_TEMPLATE_FILE_BYTES, MirrorPaths.MAX_TEMPLATE_BYTES);
            return null;
        }
        entries.sort((a, b) -> a.path().compareTo(b.path()));
        // Two paths that differ in case only would land on one file on Windows and macOS.
        Set<String> folded = new HashSet<>();
        entries.removeIf(entry -> {
            boolean duplicate = !folded.add(entry.path().toLowerCase(Locale.ROOT));
            if (duplicate) {
                skipped[0]++;
            }
            return duplicate;
        });
        if (skipped[0] > 0) {
            LOG.log(System.Logger.Level.DEBUG, "Cluster: template {0}: {1} entries left out (links, special files, "
                    + "secrets, unsafe names)", name, skipped[0]);
        }
        long size = entries.stream().mapToLong(FileEntry::size).sum();
        Map<String, FileEntry> byPath = new HashMap<>();
        entries.forEach(entry -> byPath.put(entry.path(), entry));
        return new Template(name, root, MirrorMessages.digest(entries), List.copyOf(entries), size, Map.copyOf(byPath));
    }

    private byte[] hash(Path file, BasicFileAttributes attributes) {
        Cached cached = hashes.get(file);
        if (cached != null && cached.size() == attributes.size() && cached.modified().equals(attributes.lastModifiedTime())) {
            return cached.sha256();
        }
        MessageDigest sha = MirrorMessages.sha256();
        long read = 0;
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            int n;
            while ((n = channel.read(buffer)) >= 0) {
                buffer.flip();
                sha.update(buffer);
                buffer.clear();
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        if (read != attributes.size()) {
            return null; // changed while it was read; the next scan takes it
        }
        byte[] digest = sha.digest();
        hashes.put(file, new Cached(attributes.size(), attributes.lastModifiedTime(), digest));
        return digest;
    }

    static String relative(Path root, Path path) {
        Path relative = root.relativize(path);
        StringBuilder out = new StringBuilder();
        for (Path part : relative) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }

    /** {@link MirrorMessages#TEMPLATE_LIST}: every template offered. */
    byte[] list(byte[] payload) throws ClusterRpcException {
        ManifestRequest request = ConfigMirrorSource.read(() -> ManifestRequest.read(new PacketReader(payload)));
        if (!request.template().isEmpty() || request.page() != 0) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        List<TemplateEntry> entries = new ArrayList<>();
        new TreeMap<>(catalog).values().forEach(t -> entries.add(t.entry()));
        return new TemplateList(entries).write();
    }

    /** {@link MirrorMessages#TEMPLATE_MANIFEST}: a page of a template's manifest. */
    byte[] manifest(byte[] payload) throws ClusterRpcException {
        ManifestRequest request = ConfigMirrorSource.read(() -> ManifestRequest.read(new PacketReader(payload)));
        Template template = request.template().isEmpty() ? null : catalog.get(request.template());
        if (template == null) {
            throw new ClusterRpcException(ClusterRpcException.NOT_FOUND);
        }
        if (request.page() > 0 && !Arrays.equals(request.digest(), template.digest())) {
            throw new ClusterRpcException(ClusterRpcException.CHANGED);
        }
        int from = request.page() * MirrorMessages.TEMPLATE_PAGE;
        if (from > template.entries().size()) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        int to = Math.min(template.entries().size(), from + MirrorMessages.TEMPLATE_PAGE);
        return new Manifest(0, template.digest(), template.entries().size(), request.page(),
                template.entries().subList(from, to)).write();
    }

    /** {@link MirrorMessages#TEMPLATE_CHUNK}: a chunk of a template file. */
    byte[] chunk(byte[] payload) throws ClusterRpcException {
        ChunkRequest request = ConfigMirrorSource.read(() -> ChunkRequest.read(new PacketReader(payload)));
        Template template = request.template().isEmpty() ? null : catalog.get(request.template());
        if (template == null) {
            throw new ClusterRpcException(ClusterRpcException.NOT_FOUND);
        }
        if (!Arrays.equals(request.digest(), template.digest())) {
            throw new ClusterRpcException(ClusterRpcException.CHANGED);
        }
        FileEntry entry = template.find(request.path());
        if (entry == null) {
            throw new ClusterRpcException(ClusterRpcException.NOT_FOUND);
        }
        if (request.offset() >= entry.size()) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        try {
            return read(template.root(), entry, request.offset(), request.length());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cluster: template file {0}/{1} not served: {2}", template.name(),
                    entry.path(), e.getMessage());
            throw new ClusterRpcException(ClusterRpcException.CHANGED);
        }
    }

    /**
     * Reads a chunk after checking that every part of the path is a real folder or file inside
     * the template, not a link, and that the file still has its size.
     */
    static byte[] read(Path root, FileEntry entry, long offset, int length) throws IOException {
        Path file = root;
        String[] segments = entry.path().split("/");
        for (int i = 0; i < segments.length; i++) {
            file = file.resolve(segments[i]);
            RegularPaths.Kind kind = RegularPaths.kind(file);
            RegularPaths.Kind wanted = i == segments.length - 1 ? RegularPaths.Kind.FILE : RegularPaths.Kind.DIRECTORY;
            if (kind != wanted) {
                throw new IOException("no longer a regular file inside the template");
            }
        }
        if (!file.normalize().startsWith(root.normalize())) {
            throw new IOException("outside the template");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (!file.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("outside the template");
            }
            if (channel.size() != entry.size()) {
                throw new IOException("changed since the scan");
            }
            int wanted = (int) Math.min(length, entry.size() - offset);
            ByteBuffer buffer = ByteBuffer.allocate(wanted);
            channel.position(offset);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("changed since the scan");
                }
            }
            return buffer.array();
        }
    }
}
