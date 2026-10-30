package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.vectolyn.cloud.cluster.MirrorMessages.ChunkRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.Manifest;
import net.vectolyn.cloud.cluster.MirrorMessages.ManifestRequest;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.ProtocolException;

/**
 * The head's side of the config mirror: renders {@code nodes.yml}, the groups and the tasks of its
 * snapshot with {@link ConfigYaml#write} (the same bytes the head has on disk), keeps a revision
 * that rises whenever their digest changes (persisted in {@code state/cluster/config-revision},
 * so it keeps rising across restarts) and answers the members' manifest and chunk requests.
 * Nothing else of the configuration ever leaves the head ({@link MirrorPaths#isMirroredConfig}).
 */
final class ConfigMirrorSource {

    private static final System.Logger LOG = System.getLogger(ConfigMirrorSource.class.getName());

    /**
     * One revision of the mirrored files.
     *
     * @param revision the revision
     * @param digest   digest of the files
     * @param entries  the manifest, sorted by path
     * @param files    the content by path
     */
    record Rendered(long revision, byte[] digest, List<FileEntry> entries, SortedMap<String, byte[]> files) {
    }

    private final Path stateFile;
    private final Supplier<ConfigSnapshot> snapshot;
    private volatile Rendered current;

    /**
     * Creates the source; nothing is rendered until {@link #refresh()}.
     *
     * @param stateFile {@code state/cluster/config-revision}
     * @param snapshot  the head's configuration
     */
    ConfigMirrorSource(Path stateFile, Supplier<ConfigSnapshot> snapshot) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** The mirrored files of a snapshot, by path relative to {@code config/}. */
    static SortedMap<String, byte[]> render(ConfigSnapshot snapshot) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.put(CloudHome.NODES_FILE, ConfigYaml.write(snapshot.nodes()));
        snapshot.groups().forEach((name, group) -> files.put(CloudHome.groupFileName(name), ConfigYaml.write(group)));
        snapshot.tasks().forEach((name, task) -> files.put(CloudHome.taskFileName(name), ConfigYaml.write(task)));
        return files;
    }

    /** The manifest entries of rendered files, sorted by path. */
    static List<FileEntry> entries(SortedMap<String, byte[]> files) {
        List<FileEntry> entries = new ArrayList<>();
        files.forEach((path, content) -> entries.add(new FileEntry(path, content.length, MirrorMessages.sha256(content))));
        return entries;
    }

    /**
     * Renders the snapshot again; a new digest gets the next revision, which is written to the
     * state file before anyone sees it.
     *
     * @return whether the revision changed
     * @throws IOException if the state file cannot be written
     */
    synchronized boolean refresh() throws IOException {
        SortedMap<String, byte[]> files = render(snapshot.get());
        if (files.size() > MirrorPaths.MAX_CONFIG_FILES) {
            LOG.log(System.Logger.Level.ERROR, "Cluster: more than {0} config files; the members refuse the mirror "
                    + "until there are fewer", MirrorPaths.MAX_CONFIG_FILES);
        }
        List<FileEntry> entries = entries(files);
        byte[] digest = MirrorMessages.digest(entries);
        Rendered before = current;
        if (before != null && Arrays.equals(before.digest(), digest)) {
            return false;
        }
        long revision;
        if (before != null) {
            revision = before.revision() + 1;
        } else {
            Optional<MirrorState.Revision> stored = readStored();
            revision = stored.map(s -> s.sameDigest(digest) ? s.revision() : s.revision() + 1).orElse(1L);
        }
        MirrorState.writeRevision(stateFile, new MirrorState.Revision(revision, digest));
        current = new Rendered(revision, digest, List.copyOf(entries), files);
        return true;
    }

    private Optional<MirrorState.Revision> readStored() {
        try {
            return MirrorState.readRevision(stateFile);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: {0}; the config revision starts over from 1, members "
                    + "with a higher one refuse it until it passes theirs", e.getMessage());
            return Optional.empty();
        }
    }

    /** The current revision, 0 before the first {@link #refresh()}. */
    long revision() {
        Rendered rendered = current;
        return rendered == null ? 0 : rendered.revision();
    }

    /** {@link MirrorMessages#CONFIG_MANIFEST}: a page of the manifest of the current revision. */
    byte[] manifest(NodeId peer, byte[] payload) throws ClusterRpcException {
        ManifestRequest request = read(() -> ManifestRequest.read(new PacketReader(payload)));
        Rendered rendered = current;
        if (!request.template().isEmpty()) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        if (rendered == null) {
            throw new ClusterRpcException(ClusterRpcException.NOT_FOUND);
        }
        if (request.page() > 0 && !Arrays.equals(request.digest(), rendered.digest())) {
            throw new ClusterRpcException(ClusterRpcException.CHANGED);
        }
        int from = request.page() * MirrorMessages.CONFIG_PAGE;
        if (from > rendered.entries().size()) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        int to = Math.min(rendered.entries().size(), from + MirrorMessages.CONFIG_PAGE);
        LOG.log(System.Logger.Level.DEBUG, "Cluster: config manifest page {0} for {1}", request.page(), peer);
        return new Manifest(rendered.revision(), rendered.digest(), rendered.entries().size(), request.page(),
                rendered.entries().subList(from, to)).write();
    }

    /** {@link MirrorMessages#CONFIG_CHUNK}: a chunk of a file of the current revision. */
    byte[] chunk(byte[] payload) throws ClusterRpcException {
        ChunkRequest request = read(() -> ChunkRequest.read(new PacketReader(payload)));
        Rendered rendered = current;
        if (!request.template().isEmpty() || !MirrorPaths.isMirroredConfig(request.path())) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        if (rendered == null || !Arrays.equals(request.digest(), rendered.digest())) {
            throw new ClusterRpcException(ClusterRpcException.CHANGED);
        }
        byte[] content = rendered.files().get(request.path());
        if (content == null) {
            throw new ClusterRpcException(ClusterRpcException.NOT_FOUND);
        }
        if (request.offset() >= content.length) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
        int from = (int) request.offset();
        return Arrays.copyOfRange(content, from, Math.min(content.length, from + request.length()));
    }

    /** Decodes a request; a broken one is answered, not a reason to end the session. */
    static <T> T read(Supplier<T> decoder) throws ClusterRpcException {
        try {
            return decoder.get();
        } catch (ProtocolException e) {
            throw new ClusterRpcException(ClusterRpcException.BAD_REQUEST);
        }
    }
}
