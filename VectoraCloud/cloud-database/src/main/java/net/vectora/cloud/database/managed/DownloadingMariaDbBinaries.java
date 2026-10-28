package net.vectora.cloud.database.managed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.download.DownloadException;
import net.vectora.cloud.download.archive.SafeExtractor;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.cache.DownloadedArtifact;
import net.vectora.cloud.download.provider.ArtifactProvider;
import net.vectora.cloud.download.provider.ArtifactRequest;
import net.vectora.cloud.download.provider.MariaDbProvider;
import net.vectora.cloud.download.provider.ResolvedArtifact;
import net.vectora.cloud.download.provider.TargetPlatform;

/**
 * Downloads the MariaDB server through {@code cloud-download} (MariaDB REST API, checksum checked)
 * and unpacks it below the database home with the zip-slip-safe extractor.
 *
 * <p>After the first install a marker file ({@value #MARKER}) records folder, version and SHA-256.
 * Later starts use that folder without asking the API, as long as it still holds the server and
 * the recorded version fits the wanted one. Updates of the server are a separate step (stage 2,
 * updates).
 */
public final class DownloadingMariaDbBinaries implements MariaDbBinaries {

    /** Marker file below the home. */
    public static final String MARKER = "mariadb-install.properties";

    private final ArtifactProvider provider;
    private final ArtifactDownloader downloader;
    private final SafeExtractor extractor;
    private final Path home;
    private final String version;

    /**
     * Creates the installer.
     *
     * @param provider the MariaDB provider ({@link MariaDbProvider})
     * @param downloader the downloader with its cache
     * @param extractor the extractor
     * @param home the database home, {@code Server-Network/Database/runtime}
     * @param version a release ({@code 11.8.9}), a series ({@code 11.8}) or {@code null} for the newest
     *     long-term-support series
     */
    public DownloadingMariaDbBinaries(ArtifactProvider provider, ArtifactDownloader downloader, SafeExtractor extractor,
            Path home, String version) {
        this.provider = Objects.requireNonNull(provider, "provider");
        if (!MariaDbProvider.ID.equals(provider.id())) {
            throw new IllegalArgumentException("MariaDB provider expected, got " + provider.id());
        }
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.version = version;
    }

    @Override
    public Path install(ManagedPlatform platform) throws DatabaseException, IOException, InterruptedException {
        Path installed = installedFolder(platform);
        if (installed != null) {
            return installed;
        }
        ResolvedArtifact resolved;
        DownloadedArtifact file;
        try {
            resolved = provider.resolve(ArtifactRequest.of("mariadb").withVersion(version).withSystem(system(platform)));
            file = downloader.download(resolved, null);
        } catch (DownloadException e) {
            throw new DatabaseException(DatabaseException.Reason.INSTALL, "Downloading MariaDB failed: "
                    + e.getMessage(), e);
        }
        Files.createDirectories(home);
        Path unpack = home.resolve(".unpack-" + UUID.randomUUID());
        try {
            String name = resolved.fileName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) {
                extractor.extractZip(file.file(), unpack);
            } else if (name.endsWith(".tar.gz")) {
                extractor.extractTarGz(file.file(), unpack);
            } else {
                throw new DatabaseException(DatabaseException.Reason.INSTALL, "Unexpected MariaDB archive type: "
                        + resolved.fileName());
            }
            Path top = singleFolder(unpack);
            Path target = home.resolve(top.getFileName().toString());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                // Never touch a folder that is already there; it may be a hand-made install.
                target = home.resolve(top.getFileName() + "-" + file.sha256().hex().substring(0, 12));
            }
            Files.move(top, target);
            if (!hasServer(target, platform)) {
                throw new DatabaseException(DatabaseException.Reason.INSTALL, "The MariaDB archive "
                        + resolved.fileName() + " holds no " + platform.executable("bin/mariadbd"));
            }
            writeMarker(target, resolved.coordinate().version(), file.sha256().hex());
            return target;
        } finally {
            deleteIfEmptyTree(unpack);
        }
    }

    /** Returns the recorded install if it is still usable for the wanted version, else null. */
    private Path installedFolder(ManagedPlatform platform) throws IOException {
        Path marker = home.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            p.load(in);
        }
        String folder = p.getProperty("folder", "");
        String installedVersion = p.getProperty("version", "");
        if (folder.isEmpty() || folder.contains("/") || folder.contains("\\") || folder.startsWith(".")) {
            return null;
        }
        Path dir = home.resolve(folder);
        if (!hasServer(dir, platform) || !versionFits(version, installedVersion)) {
            return null;
        }
        return dir;
    }

    /**
     * Tells whether an installed version satisfies the wanted one.
     *
     * @param wanted release, series or {@code null}
     * @param installed installed release
     * @return whether no download is needed
     */
    static boolean versionFits(String wanted, String installed) {
        if (installed == null || installed.isEmpty()) {
            return false;
        }
        if (wanted == null) {
            return true;
        }
        return installed.equals(wanted) || installed.startsWith(wanted + ".");
    }

    private void writeMarker(Path folder, String installedVersion, String sha256) throws IOException {
        Properties p = new Properties();
        p.setProperty("folder", folder.getFileName().toString());
        p.setProperty("version", installedVersion);
        p.setProperty("sha256", sha256);
        Path marker = home.resolve(MARKER);
        Path temp = home.resolve(MARKER + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            p.store(out, "Managed MariaDB, written by VectoraCloud");
        }
        Files.move(temp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean hasServer(Path base, ManagedPlatform platform) {
        return Files.isRegularFile(base.resolve("bin").resolve(platform.executable("mariadbd")));
    }

    private static Path singleFolder(Path dir) throws IOException, DatabaseException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        }
        if (entries.size() != 1 || !Files.isDirectory(entries.get(0), LinkOption.NOFOLLOW_LINKS)) {
            throw new DatabaseException(DatabaseException.Reason.INSTALL,
                    "The MariaDB archive does not contain exactly one top-level folder");
        }
        return entries.get(0);
    }

    private static TargetPlatform system(ManagedPlatform platform) {
        return switch (platform) {
            case WINDOWS_X64 -> new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64,
                    TargetPlatform.Libc.NONE);
            case LINUX_X64_GLIBC -> new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64,
                    TargetPlatform.Libc.GLIBC);
        };
    }

    /** Deletes the temporary unpack folder: what is left after a failure, or the empty folder. */
    private static void deleteIfEmptyTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
