package net.vectora.cloud.node.jre;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.download.cache.ArtifactDownloader;
import net.vectora.cloud.download.jre.JavaRequirement;
import net.vectora.cloud.download.jre.JreManager;
import net.vectora.cloud.download.jre.SystemJavaFinder;
import net.vectora.cloud.download.lock.SoftwareLockStore;
import net.vectora.cloud.download.provider.AdoptiumProvider;
import net.vectora.cloud.download.provider.ProviderRegistry;
import net.vectora.cloud.download.provider.TargetPlatform;
import net.vectora.cloud.node.service.JavaRuntimeResolver;

/**
 * The node's {@link JavaRuntimeResolver} on top of {@link JreManager}: every group gets the Java
 * that {@link JavaRequirement} picks for it.
 *
 * <ul>
 *   <li>PandaSpigot (the 1.8.8 groups) gets Java {@value JavaRequirement#LEGACY_FEATURE}, every
 *       other software Java {@value JavaRequirement#DEFAULT_FEATURE};</li>
 *   <li>{@code java: <major>} in the group file overrides that, unless it is below the minimum
 *       of the server software;</li>
 *   <li>the minimum is the highest of the known ones ({@link JavaRequirement#minimumOf}): Mojang's
 *       {@code javaVersion} and Fill's {@code java.version.minimum}. They come from the download
 *       step through {@link Minimums} ({@code SoftwareInstaller} in the node), which runs before
 *       the runtime is resolved.</li>
 * </ul>
 * The runtime is an installed system Java with exactly that version, or a Temurin JRE that
 * {@link JreManager} downloads into {@code <home>/jre/} and pins in {@code software.lock.yml}.
 */
public final class ManagedJavaRuntimes implements JavaRuntimeResolver {

    /** Where a group's Java minimum comes from. */
    @FunctionalInterface
    public interface Minimums {

        /** No minimum is known for any group. */
        Minimums NONE = (name, group) -> {
            Objects.requireNonNull(group, "group");
            return new OptionalInt[0];
        };

        /**
         * Returns the minimums the sources know for a group, for example Mojang's and Fill's.
         * Asked for each start, after the server software of that start is in place.
         *
         * @param groupName the group's name
         * @param group the group
         * @return the minimums; empty entries and an empty array are fine
         * @throws IOException if a source cannot be asked
         */
        OptionalInt[] of(String groupName, GroupConfig group) throws IOException;
    }

    /** Provides a runtime for a requirement; {@code JreManager::provide} in production. */
    @FunctionalInterface
    public interface Provider {

        /**
         * Provides the runtime.
         *
         * @param requirement the group's requirement
         * @return the runtime
         * @throws IOException if it cannot be found, downloaded or installed
         * @throws InterruptedException if interrupted
         */
        net.vectora.cloud.download.jre.JavaRuntime provide(JavaRequirement requirement)
                throws IOException, InterruptedException;
    }

    private final Provider provider;
    private final Minimums minimums;

    /**
     * Creates the resolver.
     *
     * @param provider where runtimes come from, normally {@code jreManager::provide}
     * @param minimums where the minimums come from
     */
    public ManagedJavaRuntimes(Provider provider, Minimums minimums) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.minimums = Objects.requireNonNull(minimums, "minimums");
    }

    /**
     * Builds the {@link JreManager} of a home: system Java first, then the runtimes in
     * {@code <home>/jre/}, pinned in {@code <home>/software.lock.yml} (the same store every other
     * download of the node uses).
     *
     * @param home the cloud home
     * @param providers the download providers; must contain Adoptium
     * @param downloader the downloader
     * @param lockStore {@code SoftwareLockStore.inHome(home)}
     * @param platform the platform the servers run on
     * @return the manager
     */
    public static JreManager manager(Path home, ProviderRegistry providers, ArtifactDownloader downloader,
            SoftwareLockStore lockStore, TargetPlatform platform) {
        return new JreManager(home, providers.get(AdoptiumProvider.ID), downloader, lockStore,
                SystemJavaFinder.createDefault(), platform);
    }

    /**
     * The requirement of a group.
     *
     * @param group the group
     * @param minimums the known minimums
     * @return what {@link JavaRequirement#choose()} decides on
     */
    public static JavaRequirement requirement(GroupConfig group, OptionalInt... minimums) {
        String software = group.software().name().toLowerCase(Locale.ROOT);
        return JavaRequirement.forGroup(software, JavaRequirement.minimumOf(minimums), group.java().major());
    }

    @Override
    public JavaRuntime resolve(String group, GroupConfig config) throws IOException {
        Objects.requireNonNull(group, "group");
        JavaRequirement requirement = requirement(config, minimums.of(group, config));
        try {
            net.vectora.cloud.download.jre.JavaRuntime runtime = provider.provide(requirement);
            return new JavaRuntime(runtime.executable(), runtime.feature());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while preparing Java for group " + group);
        }
    }
}
