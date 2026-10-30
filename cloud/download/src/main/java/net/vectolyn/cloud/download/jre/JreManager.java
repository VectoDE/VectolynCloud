package net.vectolyn.cloud.download.jre;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import net.vectolyn.cloud.download.archive.ExtractionLimits;
import net.vectolyn.cloud.download.cache.ArtifactDownloader;
import net.vectolyn.cloud.download.cache.DownloadedArtifact;
import net.vectolyn.cloud.download.lock.LockedArtifact;
import net.vectolyn.cloud.download.lock.SoftwareLockStore;
import net.vectolyn.cloud.download.provider.AdoptiumProvider;
import net.vectolyn.cloud.download.provider.ArtifactProvider;
import net.vectolyn.cloud.download.provider.ResolvedArtifact;
import net.vectolyn.cloud.download.provider.TargetPlatform;

/**
 * Provides the Java runtime for a server group. This is the entry point for {@code cloud-node}:
 *
 * <pre>{@code
 * JavaRequirement requirement = JavaRequirement.forGroup(group.software(), minimum, group.java().major());
 * JavaRuntime java = jres.provide(requirement);
 * new ProcessBuilder(java.executable().toString(), "-Xmx768M", "-jar", "paper.jar", "--nogui");
 * }</pre>
 *
 * <p>For a feature version it tries, in this order:
 * <ol>
 *   <li>an installed system Java with exactly that feature version ({@link SystemJavaFinder}), if
 *       system Java is allowed;</li>
 *   <li>the runtime the cloud installed before, if it comes from the archive pinned in
 *       {@code software.lock.yml};</li>
 *   <li>a download: the pinned archive again if the lockfile has one, otherwise the newest Temurin JRE
 *       from Adoptium, which is then pinned under {@code jre-temurin-<feature>-<os>-<arch>}.</li>
 * </ol>
 *
 * <p>{@link #update(int)} looks for a newer Temurin release and installs it; the lockfile keeps the
 * previous entry for a rollback.
 */
public final class JreManager {

    /** Vendor part of install and lock names. */
    public static final String VENDOR = "temurin";

    private static final System.Logger LOG = System.getLogger(JreManager.class.getName());

    private final ArtifactProvider adoptium;
    private final ArtifactDownloader downloader;
    private final SoftwareLockStore lockStore;
    private final JreInstaller installer;
    private final SystemJavaFinder systemJava;
    private final TargetPlatform platform;
    private final ConcurrentMap<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Creates the manager.
     *
     * @param cloudHome the cloud home; runtimes go to {@code jre/}
     * @param adoptium the Adoptium provider (id {@value AdoptiumProvider#ID})
     * @param downloader the downloader; its registry must contain the Adoptium provider
     * @param lockStore the store of {@code software.lock.yml}
     * @param systemJava where to look for installed Java, or {@code null} to always use managed runtimes
     * @param platform the platform the servers run on
     */
    public JreManager(Path cloudHome, ArtifactProvider adoptium, ArtifactDownloader downloader,
            SoftwareLockStore lockStore, SystemJavaFinder systemJava, TargetPlatform platform) {
        this.adoptium = Objects.requireNonNull(adoptium, "adoptium");
        if (!AdoptiumProvider.ID.equals(adoptium.id())) {
            throw new IllegalArgumentException("expected the " + AdoptiumProvider.ID + " provider, got " + adoptium.id());
        }
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.lockStore = Objects.requireNonNull(lockStore, "lockStore");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.installer = new JreInstaller(cloudHome, ExtractionLimits.defaults(), platform);
        this.systemJava = systemJava;
    }

    /**
     * Returns the name of a runtime in {@code software.lock.yml}.
     *
     * @param feature the Java feature version
     * @param platform the platform
     * @return for example {@code jre-temurin-21-linux-x64}
     */
    public static String lockName(int feature, TargetPlatform platform) {
        return "jre-" + JreInstaller.installName(VENDOR, feature, platform);
    }

    /**
     * Returns the installer, for example to show where runtimes live.
     *
     * @return the installer
     */
    public JreInstaller installer() {
        return installer;
    }

    /**
     * Provides the runtime a group needs.
     *
     * @param requirement the group's requirement
     * @return the runtime
     * @throws JreException if the group's Java override is below the minimum, or an archive is broken
     * @throws IOException if downloading or unpacking fails
     * @throws InterruptedException if interrupted
     */
    public JavaRuntime provide(JavaRequirement requirement) throws IOException, InterruptedException {
        JavaRequirement.Choice choice = requirement.choose();
        JavaRuntime runtime = provide(choice.feature());
        LOG.log(System.Logger.Level.DEBUG, "{0} for a group: {1}", choice, runtime);
        return runtime;
    }

    /**
     * Provides a runtime with exactly this feature version.
     *
     * @param feature the Java feature version
     * @return the runtime
     * @throws IOException if downloading or unpacking fails
     * @throws InterruptedException if interrupted
     */
    public JavaRuntime provide(int feature) throws IOException, InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(feature, key -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            if (systemJava != null) {
                Optional<JavaRuntime> system = systemJava.find(feature);
                if (system.isPresent()) {
                    return system.get();
                }
            }
            String lockName = lockName(feature, platform);
            String installName = JreInstaller.installName(VENDOR, feature, platform);
            Optional<LockedArtifact> pinned = lockStore.get(lockName);
            if (pinned.isPresent()) {
                Optional<JavaRuntime> installed = installer.installed(installName, pinned.get().sha256());
                if (installed.isPresent()) {
                    return installed.get();
                }
                LOG.log(System.Logger.Level.INFO, "Installing the pinned Java {0} runtime {1}", feature,
                        pinned.get().fileName());
                DownloadedArtifact file = downloader.download(pinned.get());
                return installer.install(installName, file.file(), pinned.get().fileName(), file.sha256(), feature);
            }
            ResolvedArtifact resolved = adoptium.resolve(AdoptiumProvider.request(feature, platform));
            return installAndPin(feature, resolved, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Installs the newest Temurin release of a feature version if it differs from the pinned one.
     *
     * @param feature the Java feature version
     * @return the managed runtime after the update
     * @throws IOException if resolving, downloading or unpacking fails; the old install then stays
     * @throws InterruptedException if interrupted
     */
    public JavaRuntime update(int feature) throws IOException, InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(feature, key -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            String lockName = lockName(feature, platform);
            String installName = JreInstaller.installName(VENDOR, feature, platform);
            ResolvedArtifact resolved = adoptium.resolve(AdoptiumProvider.request(feature, platform));
            Optional<LockedArtifact> pinned = lockStore.get(lockName);
            if (pinned.isPresent() && pinned.get().coordinate().equals(resolved.coordinate())) {
                Optional<JavaRuntime> installed = installer.installed(installName, pinned.get().sha256());
                if (installed.isPresent()) {
                    return installed.get();
                }
            }
            return installAndPin(feature, resolved, pinned.orElse(null));
        } finally {
            lock.unlock();
        }
    }

    private JavaRuntime installAndPin(int feature, ResolvedArtifact resolved, LockedArtifact pinned)
            throws IOException, InterruptedException {
        LOG.log(System.Logger.Level.INFO, "Downloading Java {0} ({1}) for {2}", feature, resolved.coordinate(), platform);
        DownloadedArtifact file = downloader.download(resolved, pinned);
        JavaRuntime runtime = installer.install(JreInstaller.installName(VENDOR, feature, platform), file.file(),
                resolved.fileName(), file.sha256(), feature);
        LockedArtifact entry = file.toLocked(resolved);
        lockStore.update(lock -> lock.put(lockName(feature, platform), entry));
        return runtime;
    }
}
