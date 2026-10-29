package net.vectora.cloud.node.software;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.TreeMap;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.download.cache.ArtifactCache;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.cache.DownloadedArtifact;
import net.vectora.cloud.download.catalog.CatalogEntry;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.lock.LockedArtifact;
import net.vectora.cloud.download.lock.SoftwareLockStore;
import net.vectora.cloud.download.provider.ArtifactCoordinate;
import net.vectora.cloud.download.provider.ArtifactProvider;
import net.vectora.cloud.download.provider.MojangProvider;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.ResolvedArtifact;

/**
 * {@link ArtifactSource} on the download module: providers, the verifying downloader, the
 * content-addressed cache and {@code software.lock.yml}.
 *
 * <ul>
 *   <li>A lock entry whose coordinate fits the catalog entry (same provider and project, and the
 *       pinned version or the group's Minecraft version where the entry uses one) is replayed with
 *       {@code ArtifactDownloader.download(locked)}: no API request, the same URL and checksum, and
 *       no network at all while the file is in {@code cache/artifacts/}.</li>
 *   <li>Otherwise the provider resolves the catalog's request, the file is downloaded and verified
 *       against the source's checksum (Fabric's launcher: trust on first use), and the entry is
 *       written to the lockfile. A coordinate that the lock already knows must keep its checksum.</li>
 *   <li>The Java minimum a source states (Fill's {@code java.version.minimum}, Mojang's
 *       {@code javaVersion} for Paper and Fabric) is kept in {@value #MINIMUMS_FILE} next to the
 *       cache, so a replay without network still knows it.</li>
 * </ul>
 */
public final class LockedDownloads implements ArtifactSource {

    private static final System.Logger LOG = System.getLogger(LockedDownloads.class.getName());

    /** Java minimums per lock name, in {@code cache/}. */
    static final String MINIMUMS_FILE = "java-minimums.properties";

    private final ProviderRegistry providers;
    private final ArtifactDownloader downloader;
    private final ArtifactCache cache;
    private final SoftwareLockStore lock;
    private final Path minimumsFile;
    private final Object minimumsLock = new Object();

    /**
     * Creates the source.
     *
     * @param providers  the download providers
     * @param downloader the downloader on {@code cache}
     * @param cache      {@code <home>/cache}
     * @param lock       {@code SoftwareLockStore.inHome(home)}, shared with every other download of the node
     */
    public LockedDownloads(ProviderRegistry providers, ArtifactDownloader downloader, ArtifactCache cache,
                           SoftwareLockStore lock) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.minimumsFile = cache.directory().getParent().resolve(MINIMUMS_FILE);
    }

    @Override
    public Fetched fetch(String lockName, CatalogEntry entry, String minecraftVersion)
            throws IOException, InterruptedException {
        LockedArtifact locked = lock.get(lockName).orElse(null);
        if (locked != null && fits(locked.coordinate(), entry, minecraftVersion)) {
            DownloadedArtifact file = downloader.download(locked);
            return new Fetched(lockName, locked.fileName(), file.sha256().hex(), readMinimum(lockName));
        }
        ArtifactProvider provider = providers.get(entry.provider());
        ResolvedArtifact resolved = provider.resolve(entry.toRequest(minecraftVersion));
        DownloadedArtifact file = downloader.download(resolved, locked);
        lock.update(current -> current.put(lockName, file.toLocked(resolved)));
        OptionalInt minimum = resolved.javaMinimum();
        if (entry.kind() == CatalogEntry.Kind.SERVER && minecraftVersion != null
                && entry.gameVersion() != CatalogEntry.GameVersionUse.NONE) {
            minimum = max(minimum, mojangMinimum(minecraftVersion));
        }
        writeMinimum(lockName, minimum);
        LOG.log(System.Logger.Level.INFO, "Downloaded {0} ({1}) and pinned it in software.lock.yml", lockName,
                resolved.coordinate());
        return new Fetched(lockName, resolved.fileName(), file.sha256().hex(), minimum);
    }

    @Override
    public void copy(Fetched fetched, Path destination) throws IOException {
        Checksum sha256 = Checksum.parse(HashAlgorithm.SHA256, fetched.sha256())
                .orElseThrow(() -> new IOException("not a SHA-256: " + fetched.lockName()));
        cache.copyTo(sha256, destination);
    }

    /**
     * Whether a pinned coordinate is what the catalog entry asks for now: same provider and
     * project, the pinned version of the entry if it has one, and the Minecraft version for an entry
     * that resolves it as its version (Paper). Entries that filter by Minecraft version have it in
     * their lock name instead.
     */
    static boolean fits(ArtifactCoordinate pinned, CatalogEntry entry, String minecraftVersion) {
        if (!pinned.provider().equals(entry.provider()) || !pinned.project().equals(entry.project())) {
            return false;
        }
        if (entry.versionOpt().isPresent()) {
            return entry.versionOpt().get().equals(pinned.version());
        }
        if (entry.gameVersion() == CatalogEntry.GameVersionUse.VERSION && minecraftVersion != null) {
            return minecraftVersion.equals(pinned.version());
        }
        return true;
    }

    /** Mojang's {@code javaVersion} for a release, or empty if Mojang cannot be asked now. */
    private OptionalInt mojangMinimum(String minecraftVersion) throws InterruptedException {
        if (!(providers.find(MojangProvider.ID).orElse(null) instanceof MojangProvider mojang)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(mojang.javaMajorVersion(minecraftVersion));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Mojang's Java version for {0} is unknown: {1}", minecraftVersion,
                    e.getMessage());
            return OptionalInt.empty();
        }
    }

    private static OptionalInt max(OptionalInt a, OptionalInt b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return OptionalInt.of(Math.max(a.getAsInt(), b.getAsInt()));
    }

    private OptionalInt readMinimum(String lockName) {
        synchronized (minimumsLock) {
            String value = readMinimums().getProperty(lockName);
            if (value == null || !value.matches("[0-9]{1,2}")) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(Integer.parseInt(value));
        }
    }

    private void writeMinimum(String lockName, OptionalInt minimum) {
        synchronized (minimumsLock) {
            Properties all = readMinimums();
            if (minimum.isPresent()) {
                all.setProperty(lockName, Integer.toString(minimum.getAsInt()));
            } else {
                all.remove(lockName);
            }
            StringWriter text = new StringWriter();
            text.write("# Written by VectoraCloud: the Java minimum the sources stated for each entry of"
                    + " software.lock.yml.\n");
            new TreeMap<>(all).forEach((key, value) -> text.write(key + "=" + value + "\n"));
            try {
                new AtomicFileWriter().write(minimumsFile, text.toString().getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot write cache/{0}: {1}", MINIMUMS_FILE, e.getMessage());
            }
        }
    }

    private Properties readMinimums() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(minimumsFile, LinkOption.NOFOLLOW_LINKS)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(minimumsFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(64 * 1024);
            properties.load(new StringReader(new String(bytes, StandardCharsets.ISO_8859_1)));
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read cache/{0}; Java minimums are asked for again",
                    MINIMUMS_FILE);
            return new Properties();
        }
        return properties;
    }
}
