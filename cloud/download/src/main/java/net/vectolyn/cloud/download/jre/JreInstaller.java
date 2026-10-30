package net.vectolyn.cloud.download.jre;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.vectolyn.cloud.download.FileNames;
import net.vectolyn.cloud.download.archive.ExtractionLimits;
import net.vectolyn.cloud.download.archive.SafeExtractor;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.provider.TargetPlatform;

/**
 * Unpacks Java runtimes into the cloud home, one directory per vendor, feature version and platform:
 * {@code <home>/storage/jre/temurin-21-linux-x64/}.
 *
 * <ul>
 *   <li>The archive (zip on Windows, tar.gz elsewhere) is unpacked with {@link SafeExtractor} into a
 *       staging directory next to the target, so no entry can leave it. TAR permission bits are kept,
 *       so {@code bin/java} stays executable on Linux and macOS.</li>
 *   <li>The Java home is found in the archive: its root, its single top-level directory, or
 *       {@code Contents/Home} below it (macOS). It must have a {@code release} file for the expected
 *       feature version and a regular {@code bin/java} that is executable on POSIX systems (not
 *       checked for Windows runtimes, whose zip has no permission bits).</li>
 *   <li>A marker file {@value #MARKER} records the archive's SHA-256, the relative Java home and the
 *       version. Then the staging directory replaces the target. An older install is moved aside and
 *       deleted; if a running server still holds files there (Windows), it is removed on the next
 *       install.</li>
 * </ul>
 */
public final class JreInstaller {

    /** Directory below the cloud home ({@code CloudHome.STORAGE_DIR} + {@code /jre}). */
    public static final String DIRECTORY = "storage/jre";

    /** Marker file in each install directory. */
    public static final String MARKER = ".vectolyn-jre.properties";

    static final String STAGING_PREFIX = ".staging-";
    static final String OLD_PREFIX = ".old-";
    private static final int MAX_MARKER_BYTES = 16 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final System.Logger LOG = System.getLogger(JreInstaller.class.getName());

    private final Path directory;
    private final SafeExtractor extractor;
    private final boolean windows;
    private final ReentrantLock lock;

    /**
     * Creates an installer.
     *
     * @param cloudHome the cloud home; runtimes go to {@code storage/jre/} below it
     * @param limits the extraction limits
     * @param platform the platform the runtimes are for
     */
    public JreInstaller(Path cloudHome, ExtractionLimits limits, TargetPlatform platform) {
        this.directory = Objects.requireNonNull(cloudHome, "cloudHome").toAbsolutePath().normalize().resolve(DIRECTORY);
        this.extractor = new SafeExtractor(Objects.requireNonNull(limits, "limits"));
        this.windows = Objects.requireNonNull(platform, "platform").os() == TargetPlatform.Os.WINDOWS;
        this.lock = LOCKS.computeIfAbsent(directory, key -> new ReentrantLock());
    }

    /**
     * Returns the install name for a runtime: vendor, feature version, operating system and CPU,
     * plus {@code -musl} for musl Linux. For example {@code temurin-21-linux-x64}.
     *
     * @param vendor the vendor, for example {@code temurin}
     * @param feature the Java feature version
     * @param platform the platform
     * @return the name
     */
    public static String installName(String vendor, int feature, TargetPlatform platform) {
        String name = vendor.toLowerCase(Locale.ROOT) + "-" + feature + "-"
                + platform.os().name().toLowerCase(Locale.ROOT) + "-" + platform.arch().name().toLowerCase(Locale.ROOT)
                + (platform.libc() == TargetPlatform.Libc.MUSL ? "-musl" : "");
        return checkName(name);
    }

    /**
     * Returns the directory with all runtimes.
     *
     * @return {@code <home>/jre}
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns the install directory of a runtime.
     *
     * @param name the install name
     * @return {@code <home>/storage/jre/<name>}
     */
    public Path installDirectory(String name) {
        return directory.resolve(checkName(name));
    }

    /**
     * Looks up an installed runtime.
     *
     * @param name the install name
     * @param sha256 the SHA-256 of the archive it must come from, or {@code null} for any
     * @return the runtime, if it is installed completely and from that archive
     * @throws IOException if the marker cannot be read
     */
    public Optional<JavaRuntime> installed(String name, Checksum sha256) throws IOException {
        Path target = installDirectory(name);
        Path markerFile = target.resolve(MARKER);
        if (!Files.isRegularFile(markerFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Optional<Marker> marker;
        try (InputStream in = Files.newInputStream(markerFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_MARKER_BYTES + 1);
            marker = bytes.length > MAX_MARKER_BYTES ? Optional.empty()
                    : Marker.parse(new String(bytes, StandardCharsets.UTF_8));
        }
        if (marker.isEmpty() || (sha256 != null && !marker.get().sha256().matches(sha256))) {
            return Optional.empty();
        }
        Path home = target.resolve(marker.get().home()).normalize();
        Path executable = home.resolve("bin").resolve(JavaRuntime.executableName(windows));
        if (!home.startsWith(target) || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !home.toRealPath().startsWith(target.toRealPath())) {
            return Optional.empty();
        }
        return Optional.of(new JavaRuntime(home, executable, marker.get().feature(), marker.get().version(),
                JavaRuntime.Origin.MANAGED));
    }

    /**
     * Unpacks a runtime archive and makes it the install of that name.
     *
     * @param name the install name, for example {@code temurin-21-linux-x64}
     * @param archive the verified archive (zip or tar.gz)
     * @param fileName the archive's file name, which tells the format
     * @param sha256 the archive's SHA-256, recorded in the marker
     * @param feature the Java feature version the archive must contain
     * @return the installed runtime
     * @throws JreException if the archive is not the runtime it should be
     * @throws IOException if unpacking or moving fails; an existing install then stays as it was
     */
    public JavaRuntime install(String name, Path archive, String fileName, Checksum sha256, int feature)
            throws IOException {
        Path target = installDirectory(name);
        FileNames.requireSafeBaseName(fileName, "fileName");
        if (sha256.algorithm() != HashAlgorithm.SHA256) {
            throw new IllegalArgumentException("sha256 must be a SHA-256 checksum");
        }
        lock.lock();
        try {
            Files.createDirectories(directory);
            removeLeftovers();
            Path staging = Files.createTempDirectory(directory, STAGING_PREFIX + name + "-");
            boolean moved = false;
            try {
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip")) {
                    extractor.extractZip(archive, staging);
                } else if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
                    extractor.extractTarGz(archive, staging);
                } else {
                    throw new JreException("Unknown archive type for a Java runtime: " + fileName);
                }
                Path home = findHome(staging, fileName);
                JavaReleaseFile release = JavaReleaseFile.read(home).orElseThrow(() ->
                        new JreException(fileName + " has no readable release file in its Java home"));
                OptionalInt found = release.feature();
                if (found.isEmpty() || found.getAsInt() != feature) {
                    throw new JreException(fileName + " contains Java " + release.javaVersion().orElse("of unknown version")
                            + ", expected Java " + feature);
                }
                Path executable = home.resolve("bin").resolve(JavaRuntime.executableName(windows));
                // Windows runtimes come as zip without permission bits, and java.exe needs none.
                if (!windows && posix(staging) && !Files.isExecutable(executable)) {
                    throw new JreException(fileName + ": " + staging.relativize(executable)
                            + " is not executable after unpacking");
                }
                String relativeHome = staging.relativize(home).toString().replace('\\', '/');
                String version = release.javaVersion().orElse(Integer.toString(feature));
                writeMarker(staging.resolve(MARKER), new Marker(sha256, relativeHome.isEmpty() ? "." : relativeHome,
                        feature, version));
                replace(staging, target);
                moved = true;
                return installed(name, sha256).orElseThrow(() ->
                        new JreException("Java runtime " + name + " is not usable after installing it"));
            } finally {
                if (!moved) {
                    deleteTree(staging);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes staging and old directories that an interrupted or locked install left behind.
     *
     * @return how many were deleted
     */
    public int removeLeftovers() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        lock.lock();
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                if ((fileName.startsWith(STAGING_PREFIX) || fileName.startsWith(OLD_PREFIX)) && deleteTree(entry)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list {0}: {1}", directory, e.getMessage());
        } finally {
            lock.unlock();
        }
        return removed;
    }

    private Path findHome(Path root, String fileName) throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(root);
        List<Path> topLevel = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    topLevel.add(entry);
                }
            }
        }
        if (topLevel.size() == 1) {
            candidates.add(topLevel.get(0));
            candidates.add(topLevel.get(0).resolve("Contents").resolve("Home"));
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(candidate.resolve(JavaReleaseFile.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(candidate.resolve("bin").resolve(JavaRuntime.executableName(windows)),
                            LinkOption.NOFOLLOW_LINKS)
                    && candidate.toRealPath().startsWith(root.toRealPath())) {
                return candidate;
            }
        }
        throw new JreException(fileName + " does not contain a Java home with release and bin/"
                + JavaRuntime.executableName(windows));
    }

    private void replace(Path staging, Path target) throws IOException {
        Path old = null;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            old = directory.resolve(OLD_PREFIX + target.getFileName() + "-" + Long.toHexString(System.nanoTime()));
            move(target, old);
        }
        try {
            move(staging, target);
        } catch (IOException e) {
            if (old != null) {
                try {
                    move(old, target);
                } catch (IOException restore) {
                    e.addSuppressed(restore);
                }
            }
            throw e;
        }
        if (old != null && !deleteTree(old)) {
            LOG.log(System.Logger.Level.INFO, "The previous Java runtime in {0} is still in use; it is removed on the "
                    + "next install", old);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static boolean posix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    /** Deletes a directory tree without following links; best effort. */
    static boolean deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        boolean posix = posix(root);
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        boolean complete = true;
        for (Path path : paths) {
            try {
                if (posix && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                }
                Files.deleteIfExists(path);
            } catch (IOException | RuntimeException e) {
                complete = false;
            }
        }
        return complete;
    }

    private static String checkName(String name) {
        if (name == null || !NAME.matcher(name).matches() || !FileNames.isSafeBaseName(name)) {
            throw new IllegalArgumentException("JRE install name must match " + NAME.pattern() + ": " + name);
        }
        return name;
    }

    private static void writeMarker(Path file, Marker marker) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("sha256", marker.sha256().hex());
        properties.setProperty("home", marker.home());
        properties.setProperty("feature", Integer.toString(marker.feature()));
        properties.setProperty("version", marker.version());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            properties.store(out, "Written by VectolynCloud. Delete this directory to make the cloud install it again.");
        }
    }

    /**
     * Content of the marker file.
     *
     * @param sha256 SHA-256 of the archive
     * @param home the Java home relative to the install directory, with {@code /}
     * @param feature the feature version
     * @param version the full version
     */
    record Marker(Checksum sha256, String home, int feature, String version) {

        /** Parses a marker; anything unexpected gives empty, never an exception. */
        static Optional<Marker> parse(String content) {
            Properties properties = new Properties();
            try (Reader in = new StringReader(content)) {
                properties.load(in);
            } catch (IOException | IllegalArgumentException e) {
                return Optional.empty();
            }
            Optional<Checksum> sha256 = Checksum.parse(HashAlgorithm.SHA256, properties.getProperty("sha256", ""));
            String home = properties.getProperty("home", "");
            OptionalInt feature = JavaReleaseFile.featureOf(properties.getProperty("feature", ""));
            String version = properties.getProperty("version", "");
            if (sha256.isEmpty() || feature.isEmpty() || !safeRelative(home) || version.isEmpty() || version.length() > 64
                    || version.chars().anyMatch(Character::isISOControl)) {
                return Optional.empty();
            }
            return Optional.of(new Marker(sha256.get(), home, feature.getAsInt(), version));
        }

        private static boolean safeRelative(String home) {
            if (home.equals(".")) {
                return true;
            }
            if (home.isEmpty() || home.length() > 512) {
                return false;
            }
            for (String part : home.split("/", -1)) {
                if (!FileNames.isSafeBaseName(part)) {
                    return false;
                }
            }
            return true;
        }
    }
}
