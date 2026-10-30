package net.vectolyn.cloud.node.software;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.vectolyn.cloud.config.model.CatalogKind;
import net.vectolyn.cloud.config.model.CatalogReference;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.download.catalog.ArtifactCatalog;
import net.vectolyn.cloud.download.catalog.CatalogEntry;
import net.vectolyn.cloud.node.service.ServicePlanner;
import net.vectolyn.cloud.node.service.ServiceSpec;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoftwareInstallerTest {

    @TempDir
    Path dir;

    /** Hands out a small file per lock name and records what was asked for. */
    static final class FakeSource implements ArtifactSource {
        final List<String> fetched = new ArrayList<>();
        final Map<String, String> fileNames = new ConcurrentHashMap<>();
        OptionalInt minimum = OptionalInt.empty();

        @Override
        public synchronized Fetched fetch(String lockName, CatalogEntry entry, String minecraftVersion) {
            fetched.add(lockName);
            String name = fileNames.getOrDefault(lockName, entry.id() + "-1.0.jar");
            return new Fetched(lockName, name, "0".repeat(64), entry.kind() == CatalogEntry.Kind.SERVER
                    ? minimum : OptionalInt.empty());
        }

        @Override
        public void copy(Fetched fetched, Path destination) throws IOException {
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, fetched.lockName(), StandardCharsets.UTF_8);
        }
    }

    private static GroupConfig group(String template, ServerSoftware software, String version,
                                     List<CatalogReference> catalog) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, template, software, version, JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("512M")), catalog, CombatSystem.MODERN);
    }

    private ServiceSpec spec(String name, GroupConfig group) throws IOException {
        Path work = dir.resolve(name);
        Files.createDirectories(work);
        return new ServiceSpec(new ServiceId(name + "-1"), name, name, 1, group, TaskMode.STATIC,
                ServicePlanner.kindOf(group), dir.resolve("template"), work, 30001);
    }

    private static Set<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return Set.of();
        }
        try (var files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void aLobbyGetsPaperItsCatalogPluginsAndThePaperBridge() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        ServiceSpec spec = spec("lobby", group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3", List.of()));

        assertEquals("paper.jar", installer.provide(spec, spec.workDirectory()));
        assertEquals("paper-26.3", Files.readString(spec.workDirectory().resolve("paper.jar")),
                "the server jar is pinned per Minecraft version");
        Set<String> plugins = names(spec.workDirectory().resolve("plugins"));
        for (String expected : List.of("floodgate-spigot-1.0.jar", "luckperms-bukkit-1.0.jar", "tab-1.0.jar",
                "placeholderapi-1.0.jar", "worldedit-1.0.jar", "worldguard-1.0.jar", ManagedFiles.FILE)) {
            assertTrue(plugins.contains(expected), expected + " in " + plugins);
        }
        assertTrue(plugins.stream().anyMatch(BridgePlugins.Bridge.PAPER::matches), plugins.toString());
        assertFalse(plugins.contains("geyser-velocity-1.0.jar"), "a proxy plugin stays on the proxy");
        assertFalse(plugins.stream().anyMatch(BridgePlugins.Bridge.VELOCITY::matches));
        assertTrue(source.fetched.contains("floodgate-spigot"), "a plugin that ignores the version has one pin");
        assertTrue(source.fetched.contains("luckperms-bukkit-26.3"), "one that filters by version has one per version");
    }

    @Test
    void theProxyGetsGeyserFloodgateViaAndTheVelocityBridge() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        ServiceSpec spec = spec("proxy", group(ServerKind.PROXY.templateFolder(), ServerSoftware.VELOCITY, null, List.of()));

        assertEquals("velocity.jar", installer.provide(spec, spec.workDirectory()));
        assertEquals("velocity", Files.readString(spec.workDirectory().resolve("velocity.jar")));
        Set<String> plugins = names(spec.workDirectory().resolve("plugins"));
        for (String expected : List.of("geyser-velocity-1.0.jar", "floodgate-velocity-1.0.jar", "viaversion-1.0.jar",
                "viabackwards-1.0.jar", "viarewind-1.0.jar", "luckperms-velocity-1.0.jar")) {
            assertTrue(plugins.contains(expected), expected + " in " + plugins);
        }
        assertTrue(plugins.stream().anyMatch(BridgePlugins.Bridge.VELOCITY::matches), plugins.toString());
    }

    @Test
    void aModServerGetsModsAndNoBridge() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        ServiceSpec spec = spec("mod", group(ServerKind.MOD.templateFolder(), ServerSoftware.FABRIC, "26.3", List.of()));

        assertEquals("fabric-server.jar", installer.provide(spec, spec.workDirectory()));
        Set<String> mods = names(spec.workDirectory().resolve("mods"));
        for (String expected : List.of("fabric-api-1.0.jar", "fabricproxy-lite-1.0.jar", "floodgate-fabric-1.0.jar",
                "lithium-1.0.jar", "chunky-1.0.jar")) {
            assertTrue(mods.contains(expected), expected + " in " + mods);
        }
        assertFalse(mods.stream().anyMatch(name -> name.startsWith("VectolynCloudBridge")), mods.toString());
        assertFalse(Files.exists(spec.workDirectory().resolve("plugins")), "Fabric has no plugin folder");
    }

    @Test
    void aLegacyServerGetsPandaSpigotAndTheLegacyBridgeOnly() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        ServiceSpec spec = spec("legacy", group("Legacy-Server", ServerSoftware.PANDASPIGOT, "1.8.8", List.of()));

        assertEquals("pandaspigot.jar", installer.provide(spec, spec.workDirectory()));
        Set<String> plugins = names(spec.workDirectory().resolve("plugins"));
        assertEquals(2, plugins.size(), plugins.toString());
        assertTrue(plugins.stream().anyMatch(BridgePlugins.Bridge.LEGACY::matches), plugins.toString());
        assertEquals(List.of("pandaspigot"), source.fetched, "pinned to 1.8.8 by the catalog, one lock entry");
    }

    @Test
    void anExplicitCatalogListReplacesTheDefaultsAndUnknownIdsFail() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        GroupConfig own = group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3",
                List.of(new CatalogReference("worldedit", CatalogKind.TRANSITIONAL)));
        ServiceSpec spec = spec("lobby", own);
        installer.provide(spec, spec.workDirectory());
        Set<String> plugins = names(spec.workDirectory().resolve("plugins"));
        assertTrue(plugins.contains("worldedit-1.0.jar"));
        assertFalse(plugins.contains("tab-1.0.jar"), "only the listed plugin, not the template's defaults");

        ServiceSpec unknown = spec("bad", group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3",
                List.of(new CatalogReference("no-such-plugin", CatalogKind.PLUGIN))));
        IOException e = assertThrows(IOException.class, () -> installer.provide(unknown, unknown.workDirectory()));
        assertTrue(e.getMessage().contains("no-such-plugin"), e.getMessage());

        ServiceSpec wrong = spec("wrong", group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3",
                List.of(new CatalogReference("geyser-velocity", CatalogKind.PLUGIN))));
        IOException mismatch = assertThrows(IOException.class, () -> installer.provide(wrong, wrong.workDirectory()));
        assertTrue(mismatch.getMessage().contains("geyser-velocity"), mismatch.getMessage());
    }

    @Test
    void aPluginThatIsNoLongerWantedIsRemovedButFilesOfTheOperatorStay() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        GroupConfig lobby = group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3", List.of());
        ServiceSpec spec = spec("lobby", lobby);
        Path plugins = spec.workDirectory().resolve("plugins");
        Files.createDirectories(plugins);
        Files.writeString(plugins.resolve("my-own-plugin.jar"), "mine");
        installer.provide(spec, spec.workDirectory());
        assertTrue(Files.exists(plugins.resolve("tab-1.0.jar")));

        // A new TAB release with another file name, and WorldGuard dropped from the group.
        source.fileNames.put("tab-26.3", "tab-2.0.jar");
        GroupConfig fewer = lobby.withCatalog(List.of(new CatalogReference("tab", CatalogKind.TRANSITIONAL)));
        ServiceSpec again = new ServiceSpec(spec.id(), "lobby", "lobby", 1, fewer, TaskMode.STATIC,
                ServicePlanner.kindOf(fewer), spec.templateDir(), spec.workDirectory(), 30001);
        installer.provide(again, again.workDirectory());
        Set<String> now = names(plugins);
        assertTrue(now.contains("tab-2.0.jar"), now.toString());
        assertFalse(now.contains("tab-1.0.jar"), "the old version is gone, so the server never loads two");
        assertFalse(now.contains("worldguard-1.0.jar"), now.toString());
        assertTrue(now.contains("my-own-plugin.jar"), "a file the cloud did not install stays");
        assertTrue(now.stream().anyMatch(BridgePlugins.Bridge.PAPER::matches), "the bridge stays");
    }

    @Test
    void theJavaMinimumOfTheServerSoftwareIsKnownAfterTheStart() throws Exception {
        FakeSource source = new FakeSource();
        source.minimum = OptionalInt.of(25);
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        GroupConfig lobby = group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3", List.of());
        assertTrue(installer.of("lobby", lobby)[0].isEmpty(), "unknown before the first start");
        installer.provide(spec("lobby", lobby), dir.resolve("lobby"));
        assertEquals(OptionalInt.of(25), installer.of("lobby", lobby)[0]);
        assertTrue(installer.of("other", lobby)[0].isEmpty(), "per group");
    }

    @Test
    void aLinkedPluginFolderIsRefused() throws Exception {
        FakeSource source = new FakeSource();
        SoftwareInstaller installer = new SoftwareInstaller(ArtifactCatalog.builtIn(), () -> source,
                BridgePlugins.fromClassPath());
        ServiceSpec spec = spec("lobby", group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3", List.of()));
        Files.writeString(spec.workDirectory().resolve("plugins"), "a file where the folder should be");
        assertThrows(IOException.class, () -> installer.provide(spec, spec.workDirectory()));
    }

    @Test
    void lockNamesCarryTheMinecraftVersionOnlyWhereTheFileDependsOnIt() throws Exception {
        ArtifactCatalog catalog = ArtifactCatalog.builtIn();
        GroupConfig paper = group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER, "26.3", List.of());
        assertEquals("paper-26.3", SoftwareInstaller.lockName(catalog.get("paper").orElseThrow(), paper));
        assertEquals("geyser-velocity", SoftwareInstaller.lockName(catalog.get("geyser-velocity").orElseThrow(), paper));
        assertEquals(Optional.of("4.3.1"), catalog.get("velocity").orElseThrow().versionOpt());
    }
}
