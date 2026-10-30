package net.vectolyn.cloud.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.TaskConfig;

/**
 * The member's side of the config mirror. A sync reads the head's manifest, refuses anything
 * outside the rules, downloads the files that differ from the ones on disk, checks each against
 * its SHA-256, parses it with the strict reader of its kind, and hands the set to
 * {@link ConfigService#applyMirror}, which validates it together with this node's own
 * {@code cloud.yml} and writes it. The record in {@code state/cluster/config-applied} is written
 * last, so a sync that stops half way runs again in full.
 */
final class ConfigMirrorSink {

    /**
     * What a sync did.
     *
     * @param revision the head's revision, now applied
     * @param changed  whether any file changed
     */
    record Outcome(long revision, boolean changed) {
    }

    private final ConfigService config;
    private final Path stateFile;

    /**
     * Creates the sink.
     *
     * @param config    this member's configuration, in mirror mode
     * @param stateFile {@code state/cluster/config-applied}
     */
    ConfigMirrorSink(ConfigService config, Path stateFile) {
        this.config = Objects.requireNonNull(config, "config");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
    }

    /** The revision taken over last, if any (also if its record cannot be read). */
    Optional<MirrorState.Revision> applied() {
        try {
            return MirrorState.readRevision(stateFile);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Takes over the head's config.
     *
     * @param session the session with the head
     * @return what changed
     * @throws MirrorException if the head's config breaks a rule or does not validate here
     * @throws IOException     if a request fails or a file cannot be written
     */
    Outcome sync(ClusterSession session) throws IOException {
        MirrorClient.Listing listing = MirrorClient.manifest(session, MirrorMessages.CONFIG_MANIFEST, "",
                MirrorMessages.CONFIG_PAGE, MirrorPaths.MAX_CONFIG_FILES, MirrorPaths.MAX_CONFIG_FILE_BYTES,
                MirrorPaths.MAX_CONFIG_BYTES);
        if (listing.revision() < 1) {
            throw new MirrorException("the head sent no revision");
        }
        boolean nodes = false;
        for (FileEntry entry : listing.entries()) {
            if (!MirrorPaths.isMirroredConfig(entry.path())) {
                throw new MirrorException(entry.path() + " is no mirrored config file");
            }
            nodes |= entry.path().equals(CloudHome.NODES_FILE);
        }
        if (!nodes) {
            throw new MirrorException("the head sent no nodes.yml");
        }
        Optional<MirrorState.Revision> applied = applied();
        if (applied.isPresent()) {
            long last = applied.get().revision();
            boolean same = applied.get().sameDigest(listing.digest());
            if (listing.revision() < last || (listing.revision() == last && !same)) {
                throw new MirrorException("the head sent revision " + listing.revision() + ", this node has "
                        + last + " already; refused as stale");
            }
        }
        CloudHome home = config.home();
        Map<String, byte[]> files = new TreeMap<>();
        boolean changed = false;
        for (FileEntry entry : listing.entries()) {
            Path local = home.resolve(entry.path());
            byte[] content = localCopy(local, entry);
            if (content == null) {
                changed = true;
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) entry.size());
                MirrorClient.fetch(session, MirrorMessages.CONFIG_CHUNK, "", listing.digest(), entry, out::writeBytes);
                content = out.toByteArray();
            }
            files.put(entry.path(), content);
        }
        NodesConfig nodesConfig = parse(CloudHome.NODES_FILE, files.get(CloudHome.NODES_FILE), ConfigFormat.NODES);
        Map<String, GroupConfig> groups = new TreeMap<>();
        Map<String, TaskConfig> tasks = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = file.getKey();
            String name = path.substring(path.indexOf('/') + 1, path.length() - CloudHome.EXTENSION.length());
            if (path.startsWith(CloudHome.GROUPS_DIR + "/")) {
                groups.put(name, parse(path, file.getValue(), ConfigFormat.GROUP));
            } else if (path.startsWith(CloudHome.TASKS_DIR + "/")) {
                tasks.put(name, parse(path, file.getValue(), ConfigFormat.TASK));
            }
        }
        try {
            changed |= !config.applyMirror(nodesConfig, groups, tasks).isEmpty();
        } catch (ConfigException e) {
            throw new MirrorException("the head's config does not validate on this node: "
                    + e.describe(Language.EN).strip().replace(System.lineSeparator(), "; "));
        }
        MirrorState.writeRevision(stateFile, new MirrorState.Revision(listing.revision(), listing.digest()));
        return new Outcome(listing.revision(), changed);
    }

    /** The file on disk if it has exactly the entry's content, else null. */
    private static byte[] localCopy(Path local, FileEntry entry) throws IOException {
        if (RegularPaths.kind(local) != RegularPaths.Kind.FILE || Files.size(local) != entry.size()) {
            return null;
        }
        byte[] content = Files.readAllBytes(local);
        return MessageDigest.isEqual(MirrorMessages.sha256(content), entry.sha256Bytes()) ? content : null;
    }

    private static <T> T parse(String path, byte[] content, ConfigFormat<T> format) throws MirrorException {
        try {
            ConfigYaml.Decoded<T> decoded = ConfigYaml.read(path, content, format);
            if (decoded.fileVersion() != format.currentVersion()) {
                throw new MirrorException(path + " has another schema-version than this node writes");
            }
            return decoded.value();
        } catch (ConfigException e) {
            throw new MirrorException("the head's " + path + " cannot be read: "
                    + e.describe(Language.EN).strip().replace(System.lineSeparator(), "; "));
        }
    }
}
