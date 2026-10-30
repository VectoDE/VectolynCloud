package net.vectolyn.cloud.node.software;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.model.CatalogKind;
import net.vectolyn.cloud.config.model.CatalogReference;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.download.catalog.ArtifactCatalog;
import net.vectolyn.cloud.download.catalog.CatalogEntry;
import net.vectolyn.cloud.node.jre.ManagedJavaRuntimes;
import net.vectolyn.cloud.node.service.ServerSoftwareProvider;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.node.service.ServiceSpec;
import net.vectolyn.cloud.template.ServerPlatform;

/**
 * Puts the software of a service into its folder: the server jar, the plugins or mods of its group
 * from the download catalog, and the bridge plugin of its platform.
 *
 * <ul>
 *   <li><b>Server jar.</b> The catalog entry of the group's software ({@code paper},
 *       {@code velocity}, {@code fabric-server}, {@code pandaspigot}) is fetched through the
 *       {@link ArtifactSource} (checksum, pinned in {@code software.lock.yml}) and copied as
 *       {@code paper.jar}, {@code velocity.jar}, {@code fabric-server.jar} or {@code pandaspigot.jar}.</li>
 *   <li><b>Plugins and mods.</b> The group's {@code catalog} list, or, if it is empty, every
 *       plugin or mod of the catalog whose {@code targets} name the group's template folder. They go
 *       into {@code plugins/} (Paper, Velocity, PandaSpigot) or {@code mods/} (Fabric) under the file
 *       name of their source.</li>
 *   <li><b>Bridge.</b> {@link BridgePlugins}: Velocity, Paper and PandaSpigot get theirs; Fabric
 *       has none yet.</li>
 *   <li>What the cloud installed is listed in {@code .vectolyn-managed}; files of an earlier start
 *       that are no longer wanted are removed, files of the operator stay.</li>
 * </ul>
 *
 * <p>It is also the source of the Java minimums ({@link ManagedJavaRuntimes.Minimums}): what Fill and
 * Mojang state for the server software of a group, known once its jar was fetched.
 */
public final class SoftwareInstaller implements ServerSoftwareProvider, ManagedJavaRuntimes.Minimums {

    private static final System.Logger LOG = System.getLogger(SoftwareInstaller.class.getName());

    private static final Pattern LOCK_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");

    private final ArtifactCatalog catalog;
    private final Supplier<ArtifactSource> source;
    private final BridgePlugins bridges;
    private final Map<String, Object> fetching = new ConcurrentHashMap<>();
    private final Map<String, OptionalInt> minimums = new ConcurrentHashMap<>();

    /**
     * Creates the installer.
     *
     * @param catalog the download catalog, normally {@link ArtifactCatalog#builtIn()}
     * @param source  where files come from; asked on the first start that needs one, so a node
     *                whose servers are all in place builds no HTTP client
     * @param bridges the bridge plugins
     */
    public SoftwareInstaller(ArtifactCatalog catalog, Supplier<ArtifactSource> source, BridgePlugins bridges) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.source = Objects.requireNonNull(source, "source");
        this.bridges = Objects.requireNonNull(bridges, "bridges");
    }

    @Override
    public String provide(ServiceSpec spec, Path workDirectory) throws IOException {
        GroupConfig group = spec.group();
        ServerPlatform platform = ServiceManager.platformOf(group);
        String jar = ServerSoftwareProvider.jarName(group.software());
        CatalogEntry server = catalog.get(serverEntry(group.software()))
                .orElseThrow(() -> new IOException("the download catalog has no entry for " + group.software()));
        ArtifactSource files = source.get();
        ArtifactSource.Fetched fetched = fetch(files, server, group);
        minimums.put(spec.groupName(), fetched.javaMinimum());
        files.copy(fetched, workDirectory.resolve(jar));

        Path addons = folder(workDirectory, group.software() == ServerSoftware.FABRIC ? "mods" : "plugins");
        Set<String> installed = new LinkedHashSet<>();
        for (CatalogEntry entry : addonsOf(spec.groupName(), group)) {
            ArtifactSource.Fetched addon = fetch(files, entry, group);
            files.copy(addon, addons.resolve(addon.fileName()));
            installed.add(addon.fileName());
        }
        Optional<BridgePlugins.Bridge> bridge = BridgePlugins.bridgeOf(platform);
        if (bridge.isPresent()) {
            Optional<String> name = bridges.install(bridge.get(), addons);
            if (name.isPresent()) {
                installed.add(name.get());
            } else {
                LOG.log(System.Logger.Level.WARNING, "This build has no {0} jar; {1} runs without its bridge",
                        bridge.get().baseName(), spec.id());
            }
        }
        ManagedFiles.replace(addons, installed);
        return jar;
    }

    @Override
    public Optional<String> prepare(String groupName, GroupConfig group) throws IOException {
        CatalogEntry server = catalog.get(serverEntry(group.software()))
                .orElseThrow(() -> new IOException("the download catalog has no entry for " + group.software()));
        ArtifactSource files = source.get();
        ArtifactSource.Fetched fetched = fetch(files, server, group);
        minimums.put(groupName, fetched.javaMinimum());
        List<CatalogEntry> addons = addonsOf(groupName, group);
        for (CatalogEntry entry : addons) {
            fetch(files, entry, group);
        }
        String version = group.minecraftVersion() == null ? "" : " " + group.minecraftVersion();
        String kind = group.software() == ServerSoftware.FABRIC ? "mod" : "plugin";
        return Optional.of(server.id() + version + " + " + addons.size() + " " + kind + (addons.size() == 1 ? "" : "s"));
    }

    @Override
    public OptionalInt[] of(String groupName, GroupConfig group) {
        return new OptionalInt[] {minimums.getOrDefault(groupName, OptionalInt.empty())};
    }

    /** The catalog id of a server software. */
    static String serverEntry(ServerSoftware software) {
        return switch (software) {
            case PAPER -> "paper";
            case VELOCITY -> "velocity";
            case FABRIC -> "fabric-server";
            case PANDASPIGOT -> "pandaspigot";
        };
    }

    /**
     * The plugins or mods of a group: its own {@code catalog} list, or, if that is empty, the
     * catalog's defaults for its template folder.
     *
     * @param groupName the group, for messages
     * @param group     the group
     * @return the entries, in catalog or list order
     * @throws IOException if the list names an id the catalog does not have, or one that does not
     *                     fit the group's software
     */
    List<CatalogEntry> addonsOf(String groupName, GroupConfig group) throws IOException {
        List<CatalogEntry> result = new ArrayList<>();
        if (group.catalog().isEmpty()) {
            for (CatalogEntry entry : catalog.entries()) {
                if (entry.targets().contains(group.template()) && isAddon(entry) && fits(entry, group.software())) {
                    result.add(entry);
                }
            }
            return result;
        }
        for (CatalogReference reference : group.catalog()) {
            CatalogEntry entry = catalog.get(reference.id()).orElseThrow(() -> new IOException("group " + groupName
                    + ": the download catalog has no entry " + reference.id()));
            if (!isAddon(entry) || !fits(entry, group.software())) {
                throw new IOException("group " + groupName + ": " + reference.id() + " is no "
                        + (group.software() == ServerSoftware.FABRIC ? "Fabric mod" : "plugin for "
                        + group.software().name().toLowerCase(Locale.ROOT)));
            }
            if (reference.kind() == CatalogKind.TRANSITIONAL && !entry.transitional()) {
                LOG.log(System.Logger.Level.DEBUG, "group {0}: {1} is not transitional in the catalog", groupName,
                        reference.id());
            }
            result.add(entry);
        }
        return result;
    }

    private static boolean isAddon(CatalogEntry entry) {
        return entry.kind() == CatalogEntry.Kind.PLUGIN || entry.kind() == CatalogEntry.Kind.MOD;
    }

    /** Mods only on Fabric, Velocity plugins only on Velocity, the other plugins only on servers. */
    private static boolean fits(CatalogEntry entry, ServerSoftware software) {
        if (software == ServerSoftware.FABRIC) {
            return entry.kind() == CatalogEntry.Kind.MOD;
        }
        if (entry.kind() != CatalogEntry.Kind.PLUGIN) {
            return false;
        }
        boolean velocityPlugin = "velocity".equals(entry.platform());
        return velocityPlugin == (software == ServerSoftware.VELOCITY);
    }

    /**
     * The lock name of an entry for a group: its id, plus the Minecraft version for entries whose
     * file depends on it, so two groups on different versions each pin their own file.
     */
    static String lockName(CatalogEntry entry, GroupConfig group) throws IOException {
        String name = entry.gameVersion() == CatalogEntry.GameVersionUse.NONE || group.minecraftVersion() == null
                ? entry.id() : entry.id() + "-" + group.minecraftVersion().toLowerCase(Locale.ROOT);
        if (!LOCK_NAME.matcher(name).matches()) {
            throw new IOException("cannot pin " + entry.id() + " for Minecraft " + group.minecraftVersion()
                    + ": the name " + name + " is not allowed in software.lock.yml");
        }
        return name;
    }

    private ArtifactSource.Fetched fetch(ArtifactSource files, CatalogEntry entry, GroupConfig group) throws IOException {
        String name = lockName(entry, group);
        // Two services of one group starting at once download each file only once.
        Object guard = fetching.computeIfAbsent(name, key -> new Object());
        synchronized (guard) {
            try {
                return files.fetch(name, entry, group.minecraftVersion());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while downloading " + entry.id());
            }
        }
    }

    /** A plugin or mod folder below the service folder; refuses a link, which could point anywhere. */
    private static Path folder(Path workDirectory, String name) throws IOException {
        Path folder = workDirectory.resolve(name);
        if (Files.isSymbolicLink(folder) || (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(name + "/ in " + workDirectory.getFileName() + " is not a plain folder");
        }
        Files.createDirectories(folder);
        return folder;
    }
}
