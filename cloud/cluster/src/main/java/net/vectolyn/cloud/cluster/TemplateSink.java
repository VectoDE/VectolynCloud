package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.ManifestRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateList;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.ProtocolException;

/**
 * The member's side of the template sync. Every template of the head is mirrored: it is
 * downloaded when its digest changed and replaces a folder of the same name, and one the head no
 * longer has is deleted if this member synced it. A folder the mirror never synced (a built-in
 * template the node wrote for one of its wanted servers, or the operator's own) is left as it is.
 *
 * <p>A template is downloaded into {@code local/.cluster-sync/staging/<name>}, on the same file
 * system as the templates: every path is checked ({@link MirrorPaths#isSafePath}), every file is
 * created new without following links, and its size and SHA-256 are checked while it is written.
 * Any mismatch deletes the staging folder and leaves the old template as it was. Then the old
 * template is renamed to {@code local/.cluster-sync/old/<name>}, the staging folder renamed into
 * its place and the old one deleted. {@link #recover()} completes or undoes a swap a crash
 * interrupted. Files that did not change are copied from the old template instead of downloaded
 * (and checked the same way).
 */
final class TemplateSink {

    private static final System.Logger LOG = System.getLogger(TemplateSink.class.getName());
    /** The largest share of the synced templates one listing may remove (more than one). */
    static final double MAX_REMOVED_SHARE = 0.5;

    /**
     * What a sync did.
     *
     * @param templates templates the head has
     * @param updated   templates downloaded
     * @param removed   templates removed because the head no longer has them
     */
    record Outcome(int templates, int updated, int removed) {
    }

    private final Path templatesDir;
    private final Path staging;
    private final Path old;
    private final Path stateFile;
    /** Folders not managed by the mirror that were logged already, so each is logged once. */
    private final Set<String> unmanagedLogged = ConcurrentHashMap.newKeySet();

    /**
     * Creates the sink.
     *
     * @param templatesDir {@code local/templates}
     * @param workDir      {@code local/.cluster-sync}
     * @param stateFile    {@code state/cluster/templates}
     */
    TemplateSink(Path templatesDir, Path workDir, Path stateFile) {
        this.templatesDir = Objects.requireNonNull(templatesDir, "templatesDir");
        this.staging = workDir.resolve("staging");
        this.old = workDir.resolve("old");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
    }

    /** The templates synced last, by name with their digest. */
    Map<String, byte[]> synced() {
        try {
            return MirrorState.readTemplates(stateFile);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: {0}; every template is synced again", e.getMessage());
            return new TreeMap<>();
        }
    }

    /**
     * Puts right a swap that a crash interrupted: a template that was renamed away but not
     * replaced comes back, an old copy next to its replacement is deleted, and staging folders are
     * removed.
     *
     * @throws IOException if a folder cannot be moved or deleted
     */
    void recover() throws IOException {
        deleteTree(staging);
        if (RegularPaths.kind(old) != RegularPaths.Kind.DIRECTORY) {
            return;
        }
        for (Path left : list(old)) {
            Path current = templatesDir.resolve(left.getFileName().toString());
            if (RegularPaths.kind(current) == RegularPaths.Kind.MISSING) {
                Files.createDirectories(templatesDir);
                Files.move(left, current, StandardCopyOption.ATOMIC_MOVE);
                LOG.log(System.Logger.Level.WARNING, "Cluster: template {0} restored after an interrupted sync",
                        left.getFileName());
            } else {
                deleteTree(left);
            }
        }
    }

    /**
     * Brings {@code local/templates/} to the head's templates.
     *
     * @param session the session with the head
     * @return what changed
     * @throws MirrorException if a template broke a rule; the others were synced
     * @throws IOException     if a request fails or a folder cannot be written
     */
    Outcome sync(ClusterSession session) throws IOException {
        TemplateList list;
        try {
            list = TemplateList.read(new PacketReader(session.call(MirrorMessages.TEMPLATE_LIST,
                    new ManifestRequest("", new byte[0], 0).write(), MirrorClient.TIMEOUT)));
        } catch (ProtocolException e) {
            throw new MirrorException("invalid template list");
        }
        Set<String> folded = new HashSet<>();
        long total = 0;
        for (TemplateEntry template : list.templates()) {
            total += template.bytes();
            if (!folded.add(template.name().toLowerCase(Locale.ROOT)) || template.files() > MirrorPaths.MAX_TEMPLATE_FILES
                    || template.bytes() > MirrorPaths.MAX_TEMPLATE_BYTES || total > MirrorPaths.MAX_TEMPLATES_BYTES) {
                throw new MirrorException("the template list breaks the limits");
            }
        }
        Map<String, byte[]> synced = synced();
        Map<String, byte[]> now = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        int updated = 0;
        for (TemplateEntry template : list.templates()) {
            Path current = templatesDir.resolve(template.name());
            byte[] had = synced.get(template.name());
            if (had != null && MessageDigest.isEqual(had, template.digest())
                    && RegularPaths.kind(current) == RegularPaths.Kind.DIRECTORY) {
                now.put(template.name(), had);
                continue;
            }
            try {
                download(session, template);
                now.put(template.name(), template.digest());
                updated++;
            } catch (MirrorException | ClusterRpcException e) {
                problems.add(template.name() + ": " + e.getMessage());
                if (had != null) {
                    now.put(template.name(), had);
                }
            }
        }
        int removed = removeOthers(list, synced, now, problems);
        MirrorState.writeTemplates(stateFile, now);
        if (!problems.isEmpty()) {
            throw new MirrorException(String.join("; ", problems));
        }
        return new Outcome(list.templates().size(), updated, removed);
    }

    /**
     * Deletes the synced templates the head no longer lists. A listing that would take away more
     * than {@link #MAX_REMOVED_SHARE} of them at once (more than one), or every one of them, is
     * not followed: that looks like a head whose templates are missing (a lost mount, a wrong
     * home), not like an operator who deleted some. They stay, keep their place in the state, and
     * the problem is reported until the head lists them again or the operator deletes them here.
     */
    private int removeOthers(TemplateList list, Map<String, byte[]> synced, Map<String, byte[]> now,
                             List<String> problems) throws IOException {
        if (RegularPaths.kind(templatesDir) != RegularPaths.Kind.DIRECTORY) {
            return 0;
        }
        Set<String> wanted = new HashSet<>();
        list.templates().forEach(t -> wanted.add(t.name()));
        List<Path> gone = new ArrayList<>();
        for (Path folder : list(templatesDir)) {
            String name = folder.getFileName().toString();
            if (wanted.contains(name) || !MirrorPaths.isTemplateName(name)
                    || RegularPaths.kind(folder) != RegularPaths.Kind.DIRECTORY) {
                continue;
            }
            if (synced.containsKey(name)) {
                gone.add(folder);
            } else if (unmanagedLogged.add(name)) {
                // A built-in template the node wrote for one of its wanted servers, or the
                // operator's own folder: not the mirror's, so it stays.
                LOG.log(System.Logger.Level.INFO, "Cluster: template {0} is not on the head; left as it is", name);
            }
        }
        if (!removalAllowed(gone.size(), synced.size(), list.templates().isEmpty())) {
            for (Path folder : gone) {
                String name = folder.getFileName().toString();
                now.put(name, synced.get(name));
            }
            LOG.log(System.Logger.Level.WARNING, "Cluster: the head no longer lists {0} of the {1} templates synced "
                    + "here; they are kept, because that many at once looks like a problem on the head. Delete them "
                    + "here by hand if they are meant to go.", gone.size(), synced.size());
            problems.add("the head no longer lists " + gone.size() + " of " + synced.size()
                    + " synced templates; kept");
            return 0;
        }
        int removed = 0;
        for (Path folder : gone) {
            String name = folder.getFileName().toString();
            deleteTree(folder);
            LOG.log(System.Logger.Level.INFO, "Cluster: template {0} removed, the head no longer has it", name);
            removed++;
        }
        return removed;
    }

    /**
     * Whether one listing may remove {@code gone} of the {@code synced} templates.
     *
     * @param gone   synced templates the listing no longer has
     * @param synced templates synced before
     * @param empty  whether the listing has no template at all
     */
    static boolean removalAllowed(int gone, int synced, boolean empty) {
        if (gone == 0) {
            return true;
        }
        if (empty) {
            return false;
        }
        return gone == 1 || gone <= synced * MAX_REMOVED_SHARE;
    }

    /** Downloads one template into staging and swaps it into place. */
    private void download(ClusterSession session, TemplateEntry template) throws IOException {
        MirrorClient.Listing listing = MirrorClient.manifest(session, MirrorMessages.TEMPLATE_MANIFEST, template.name(),
                MirrorMessages.TEMPLATE_PAGE, MirrorPaths.MAX_TEMPLATE_FILES, MirrorPaths.MAX_TEMPLATE_FILE_BYTES,
                MirrorPaths.MAX_TEMPLATE_BYTES);
        if (!MessageDigest.isEqual(listing.digest(), template.digest())) {
            throw new MirrorException("changed on the head while it was synced");
        }
        Set<String> files = new HashSet<>();
        listing.entries().forEach(entry -> files.add(entry.path()));
        for (FileEntry entry : listing.entries()) {
            if (MirrorPaths.looksSecret(entry.path())) {
                throw new MirrorException(entry.path() + " looks like a secret; refused");
            }
            for (int slash = entry.path().indexOf('/'); slash >= 0; slash = entry.path().indexOf('/', slash + 1)) {
                if (files.contains(entry.path().substring(0, slash))) {
                    throw new MirrorException(entry.path() + " lies inside a file of the same template");
                }
            }
        }
        Path target = staging.resolve(template.name());
        deleteTree(target);
        Files.createDirectories(target);
        Path current = templatesDir.resolve(template.name());
        boolean done = false;
        try {
            for (FileEntry entry : listing.entries()) {
                write(session, template, entry, target, current);
            }
            swap(target, current, template.name());
            done = true;
        } finally {
            if (!done) {
                deleteTree(target);
            }
        }
    }

    private static void write(ClusterSession session, TemplateEntry template, FileEntry entry, Path target, Path current)
            throws IOException {
        Path file = resolve(target, entry.path());
        Files.createDirectories(file.getParent());
        if (copyIfSame(current, entry, file)) {
            return;
        }
        try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            MirrorClient.fetch(session, MirrorMessages.TEMPLATE_CHUNK, template.name(), template.digest(), entry,
                    chunk -> writeFully(out, chunk));
        }
    }

    /** Copies the file of the current template if it is a real file with the same content. */
    private static boolean copyIfSame(Path current, FileEntry entry, Path file) throws IOException {
        Path source = current;
        String[] segments = entry.path().split("/");
        for (int i = 0; i < segments.length; i++) {
            source = source.resolve(segments[i]);
            RegularPaths.Kind kind = RegularPaths.kind(source);
            if (kind != (i == segments.length - 1 ? RegularPaths.Kind.FILE : RegularPaths.Kind.DIRECTORY)) {
                return false;
            }
        }
        if (Files.size(source) != entry.size()) {
            return false;
        }
        MessageDigest sha = MirrorMessages.sha256();
        long copied = 0;
        try (SeekableByteChannel in = Files.newByteChannel(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            int n;
            while ((n = in.read(buffer)) >= 0 && copied + n <= entry.size()) {
                buffer.flip();
                sha.update(buffer.duplicate());
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                buffer.clear();
                copied += n;
            }
        }
        if (copied == entry.size() && MessageDigest.isEqual(sha.digest(), entry.sha256Bytes())) {
            return true;
        }
        Files.delete(file);
        return false;
    }

    private static void writeFully(FileChannel out, byte[] chunk) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(chunk);
        while (buffer.hasRemaining()) {
            out.write(buffer);
        }
    }

    /** Resolves a checked path below a folder, segment by segment. */
    private static Path resolve(Path root, String path) throws MirrorException {
        if (!MirrorPaths.isSafePath(path)) {
            throw new MirrorException("unsafe path");
        }
        Path file = root;
        for (String segment : path.split("/")) {
            file = file.resolve(segment);
        }
        if (!file.normalize().startsWith(root.normalize())) {
            throw new MirrorException("unsafe path");
        }
        return file;
    }

    private void swap(Path ready, Path current, String name) throws IOException {
        Path aside = old.resolve(name);
        deleteTree(aside);
        Files.createDirectories(old);
        Files.createDirectories(templatesDir);
        boolean moved = false;
        if (RegularPaths.kind(current) != RegularPaths.Kind.MISSING) {
            if (RegularPaths.kind(current) != RegularPaths.Kind.DIRECTORY) {
                throw new MirrorException("local/templates/" + name + " is no regular folder");
            }
            Files.move(current, aside, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
        }
        try {
            Files.move(ready, current, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (moved) {
                Files.move(aside, current, StandardCopyOption.ATOMIC_MOVE);
            }
            throw e;
        }
        deleteTree(aside);
    }

    private static List<Path> list(Path dir) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        }
        entries.sort(null);
        return entries;
    }

    /** Deletes a folder and everything in it; links are deleted, never followed. */
    static void deleteTree(Path root) throws IOException {
        if (RegularPaths.kind(root) == RegularPaths.Kind.MISSING) {
            return;
        }
        if (RegularPaths.kind(root) != RegularPaths.Kind.DIRECTORY) {
            Files.delete(root);
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
