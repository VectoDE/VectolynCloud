package net.vectolyn.cloud.node.software;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import net.vectolyn.cloud.download.FileNames;
import net.vectolyn.cloud.template.ServerPlatform;

/**
 * The bridge plugins of the cloud ({@code docs/cloud/bridges.md}) and how a service gets its one.
 *
 * <p>The three jars travel inside the node's own jar under {@value #RESOURCE_DIR}, with
 * {@value #INDEX} listing each one with its SHA-256 (the build writes both). Velocity, also as
 * legacy proxy, gets {@code VectolynCloudBridgeVelocity-<version>.jar}, Paper
 * {@code VectolynCloudBridgePaper-<version>.jar}, PandaSpigot {@code VectolynCloudBridgeLegacy-<version>.jar};
 * Fabric has no bridge yet. The copy goes through a temporary file and an atomic move and is
 * checked against the index; other versions of the same bridge in the folder are removed, so an
 * update never leaves two bridges behind.
 */
public final class BridgePlugins {

    /** Folder of the bridge jars on the class path. */
    public static final String RESOURCE_DIR = "net/vectolyn/cloud/node/bridge/";

    /** Index of the bridge jars: {@code <sha256>  <file name>} per line. */
    public static final String INDEX = "bridges.index";

    private static final Pattern INDEX_LINE = Pattern.compile("([0-9a-f]{64})  (VectolynCloudBridge[A-Za-z]+-[A-Za-z0-9._+-]+\\.jar)");

    /** One bridge plugin. */
    public enum Bridge {
        /** Main proxy and legacy proxy. */
        VELOCITY("VectolynCloudBridgeVelocity"),
        /** Paper backends. */
        PAPER("VectolynCloudBridgePaper"),
        /** PandaSpigot 1.8.8 backends behind the legacy proxy (BungeeGuard check). */
        LEGACY("VectolynCloudBridgeLegacy");

        private final String baseName;

        Bridge(String baseName) {
            this.baseName = baseName;
        }

        /**
         * Returns the jar name without version.
         *
         * @return for example {@code VectolynCloudBridgePaper}
         */
        public String baseName() {
            return baseName;
        }

        /**
         * Tells whether a file name is a jar of this bridge, in any version.
         *
         * @param fileName a file name
         * @return whether it is {@code <base name>-<version>.jar}
         */
        public boolean matches(String fileName) {
            return fileName.startsWith(baseName + "-") && fileName.toLowerCase(Locale.ROOT).endsWith(".jar");
        }
    }

    /** Where the jar bytes come from; the class path in the node. */
    @FunctionalInterface
    public interface Source {

        /**
         * Opens one entry of {@value #RESOURCE_DIR}.
         *
         * @param name {@value #INDEX} or a jar name from it
         * @return the stream, or {@code null} if there is no such entry
         * @throws IOException if it cannot be opened
         */
        InputStream open(String name) throws IOException;
    }

    /** One jar of the index. */
    private record Entry(String fileName, String sha256) {
    }

    private final Source source;
    private volatile Map<Bridge, Entry> index;

    /**
     * Creates the installer on a source of jars.
     *
     * @param source where {@value #INDEX} and the jars are read from
     */
    public BridgePlugins(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * The installer on the node's own class path.
     *
     * @return the installer
     */
    public static BridgePlugins fromClassPath() {
        ClassLoader loader = BridgePlugins.class.getClassLoader();
        return new BridgePlugins(name -> loader.getResourceAsStream(RESOURCE_DIR + name));
    }

    /**
     * The bridge a platform runs.
     *
     * @param platform the platform of the service
     * @return the bridge, or empty for a platform without one (Fabric, the database)
     */
    public static Optional<Bridge> bridgeOf(ServerPlatform platform) {
        return switch (platform) {
            case VELOCITY, VELOCITY_LEGACY -> Optional.of(Bridge.VELOCITY);
            case PAPER -> Optional.of(Bridge.PAPER);
            case PANDASPIGOT -> Optional.of(Bridge.LEGACY);
            case FABRIC, NONE -> Optional.empty();
        };
    }

    /**
     * Tells whether a folder holds a jar of a bridge.
     *
     * @param pluginDirectory the plugin folder of a service
     * @param bridge the bridge
     * @return whether a regular file {@code <base name>-<version>.jar} is there
     * @throws IOException if the folder cannot be listed
     */
    public static boolean isInstalled(Path pluginDirectory, Bridge bridge) throws IOException {
        if (!Files.isDirectory(pluginDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var files = Files.list(pluginDirectory)) {
            return files.anyMatch(file -> bridge.matches(file.getFileName().toString())
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
        }
    }

    /**
     * The file name of a bridge in this build.
     *
     * @param bridge the bridge
     * @return the jar name, or empty if this build has no such jar
     * @throws IOException if the index cannot be read or is malformed
     */
    public Optional<String> fileName(Bridge bridge) throws IOException {
        return Optional.ofNullable(index().get(bridge)).map(Entry::fileName);
    }

    /**
     * Copies a bridge into a plugin folder and removes other versions of it there.
     *
     * @param bridge the bridge
     * @param pluginDirectory the plugin folder of the service; created if missing
     * @return the jar name, or empty if this build has no such jar
     * @throws IOException if the copy fails or the jar does not match the index
     */
    public Optional<String> install(Bridge bridge, Path pluginDirectory) throws IOException {
        Entry entry = index().get(bridge);
        if (entry == null) {
            return Optional.empty();
        }
        Files.createDirectories(pluginDirectory);
        Path target = pluginDirectory.resolve(entry.fileName());
        Path temp = Files.createTempFile(pluginDirectory, ".vectolyn-bridge-", ".tmp");
        try {
            MessageDigest digest = sha256();
            try (InputStream in = source.open(entry.fileName());
                 OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                if (in == null) {
                    throw new IOException("the bridge " + entry.fileName() + " is listed in " + INDEX + " but missing");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(entry.sha256())) {
                throw new IOException("the bridge " + entry.fileName() + " does not match " + INDEX);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        removeOtherVersions(bridge, pluginDirectory, entry.fileName());
        return Optional.of(entry.fileName());
    }

    /** Deletes other versions of the bridge: plain files only, a link is removed as a link. */
    private static void removeOtherVersions(Bridge bridge, Path pluginDirectory, String keep) throws IOException {
        try (var files = Files.list(pluginDirectory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.equals(keep) && bridge.matches(name) && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private Map<Bridge, Entry> index() throws IOException {
        Map<Bridge, Entry> current = index;
        if (current != null) {
            return current;
        }
        Map<Bridge, Entry> parsed = new LinkedHashMap<>();
        try (InputStream in = source.open(INDEX)) {
            if (in != null) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\n", -1)) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    var matcher = INDEX_LINE.matcher(trimmed);
                    if (!matcher.matches() || !FileNames.isSafeBaseName(matcher.group(2))) {
                        throw new IOException(INDEX + " has a malformed line");
                    }
                    String name = matcher.group(2);
                    for (Bridge bridge : Bridge.values()) {
                        if (bridge.matches(name) && parsed.putIfAbsent(bridge, new Entry(name, matcher.group(1))) != null) {
                            throw new IOException(INDEX + " lists " + bridge.baseName() + " twice");
                        }
                    }
                }
            }
        }
        index = Map.copyOf(parsed);
        return index;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
