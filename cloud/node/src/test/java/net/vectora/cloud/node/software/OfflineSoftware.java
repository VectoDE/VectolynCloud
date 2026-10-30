package net.vectora.cloud.node.software;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.catalog.ArtifactCatalog;
import net.vectora.cloud.download.catalog.CatalogEntry;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.lock.LockedArtifact;
import net.vectora.cloud.download.lock.SoftwareLock;
import net.vectora.cloud.download.lock.SoftwareLockStore;
import net.vectora.cloud.download.lock.Trust;
import net.vectora.cloud.download.provider.ArtifactCoordinate;
import net.vectora.cloud.setup.NetworkServer;

/**
 * Prepares a home so that every default group of the setup finds its server jar and plugins
 * without the network: one file in {@code storage/artifacts/} and a {@code config/software.lock.yml} entry
 * for each catalog entry the node will ask for, all pointing at that file. The node then replays
 * the lock exactly as on a second start after real downloads.
 */
public final class OfflineSoftware {

    private OfflineSoftware() {
    }

    /**
     * Seeds the cache and the lock of a home.
     *
     * @param home the cloud home
     * @param jar  the file every entry stands for, normally the fake server jar
     * @return per template folder, the plugin or mod file names the node installs there from the catalog
     * @throws IOException if the files cannot be written
     */
    public static Map<String, List<String>> seed(Path home, Path jar) throws IOException {
        ArtifactCatalog catalog = ArtifactCatalog.builtIn();
        SoftwareInstaller installer = new SoftwareInstaller(catalog, () -> {
            throw new IllegalStateException("not used");
        }, BridgePlugins.fromClassPath());
        byte[] bytes = Files.readAllBytes(jar);
        Checksum sha256 = Checksum.of(HashAlgorithm.SHA256, digest(bytes));
        ArtifactCache cache = new ArtifactCache(home.resolve("storage"));
        Path part = cache.newPartFile();
        Files.write(part, bytes);
        cache.commit(part, sha256);

        Map<String, LockedArtifact> entries = new TreeMap<>();
        Map<String, List<String>> addons = new LinkedHashMap<>();
        for (NetworkServer server : NetworkServer.values()) {
            GroupConfig group = new GroupConfig(GroupConfig.SCHEMA_VERSION, server.template(), server.software(),
                    server.minecraftVersion(), JavaSelection.AUTO,
                    new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("256M")), List.of(), server.combat());
            List<CatalogEntry> wanted = new ArrayList<>();
            wanted.add(catalog.get(SoftwareInstaller.serverEntry(server.software())).orElseThrow());
            List<String> names = new ArrayList<>();
            for (CatalogEntry entry : installer.addonsOf(server.key(), group)) {
                wanted.add(entry);
                names.add(fileName(entry));
            }
            addons.put(server.template(), List.copyOf(names));
            for (CatalogEntry entry : wanted) {
                entries.put(SoftwareInstaller.lockName(entry, group), locked(entry, group, sha256, bytes.length));
            }
        }
        SoftwareLockStore.inHome(home).update(current -> {
            SoftwareLock lock = current;
            for (Map.Entry<String, LockedArtifact> entry : entries.entrySet()) {
                lock = lock.put(entry.getKey(), entry.getValue());
            }
            return lock;
        });
        return addons;
    }

    /** A coordinate that {@link LockedDownloads#fits} accepts for the entry and group. */
    private static LockedArtifact locked(CatalogEntry entry, GroupConfig group, Checksum sha256, long size) {
        String version = entry.versionOpt().orElse(entry.gameVersion() == CatalogEntry.GameVersionUse.VERSION
                ? group.minecraftVersion() : "e2e");
        ArtifactCoordinate coordinate = new ArtifactCoordinate(entry.provider(), entry.project(), version, "e2e");
        return new LockedArtifact(coordinate, URI.create("https://e2e.invalid/" + fileName(entry)), fileName(entry),
                sha256, sha256, Trust.TOFU, size, null);
    }

    private static String fileName(CatalogEntry entry) {
        return entry.id() + "-e2e.jar";
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
