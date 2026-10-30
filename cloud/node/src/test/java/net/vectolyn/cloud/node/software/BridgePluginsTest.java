package net.vectolyn.cloud.node.software;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;
import net.vectolyn.cloud.template.ServerPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgePluginsTest {

    @TempDir
    Path dir;

    @Test
    void theBuildCarriesAllThreeBridgesAsRealPluginJars() throws Exception {
        BridgePlugins bridges = BridgePlugins.fromClassPath();
        for (BridgePlugins.Bridge bridge : BridgePlugins.Bridge.values()) {
            String name = bridges.fileName(bridge).orElseThrow(() -> new AssertionError(bridge + " missing"));
            assertTrue(bridge.matches(name), name);
            Path plugins = dir.resolve(bridge.name()).resolve("plugins");
            assertEquals(Optional.of(name), bridges.install(bridge, plugins));
            try (JarFile jar = new JarFile(plugins.resolve(name).toFile())) {
                String descriptor = bridge == BridgePlugins.Bridge.VELOCITY ? "velocity-plugin.json" : "plugin.yml";
                assertTrue(jar.getEntry(descriptor) != null, name + " has its " + descriptor);
            }
        }
    }

    @Test
    void eachPlatformGetsItsBridgeAndFabricNone() {
        assertEquals(Optional.of(BridgePlugins.Bridge.VELOCITY), BridgePlugins.bridgeOf(ServerPlatform.VELOCITY));
        assertEquals(Optional.of(BridgePlugins.Bridge.VELOCITY), BridgePlugins.bridgeOf(ServerPlatform.VELOCITY_LEGACY));
        assertEquals(Optional.of(BridgePlugins.Bridge.PAPER), BridgePlugins.bridgeOf(ServerPlatform.PAPER));
        assertEquals(Optional.of(BridgePlugins.Bridge.LEGACY), BridgePlugins.bridgeOf(ServerPlatform.PANDASPIGOT));
        assertTrue(BridgePlugins.bridgeOf(ServerPlatform.FABRIC).isEmpty());
        assertTrue(BridgePlugins.bridgeOf(ServerPlatform.NONE).isEmpty());
    }

    @Test
    void anOldVersionOfTheBridgeIsReplaced() throws Exception {
        Path plugins = dir.resolve("plugins");
        Files.createDirectories(plugins);
        Files.writeString(plugins.resolve("VectolynCloudBridgePaper-0.0.1.jar"), "old");
        Files.writeString(plugins.resolve("VectolynCloudBridgeVelocity-0.0.1.jar"), "another bridge, not touched");
        String name = BridgePlugins.fromClassPath().install(BridgePlugins.Bridge.PAPER, plugins).orElseThrow();
        assertFalse(Files.exists(plugins.resolve("VectolynCloudBridgePaper-0.0.1.jar")));
        assertTrue(Files.exists(plugins.resolve(name)));
        assertTrue(Files.exists(plugins.resolve("VectolynCloudBridgeVelocity-0.0.1.jar")));
        assertTrue(BridgePlugins.isInstalled(plugins, BridgePlugins.Bridge.PAPER));
        assertFalse(BridgePlugins.isInstalled(dir.resolve("nothing"), BridgePlugins.Bridge.PAPER));
    }

    @Test
    void aJarThatDoesNotMatchTheIndexIsNotInstalled() throws Exception {
        String index = "# test\n" + "0".repeat(64) + "  VectolynCloudBridgePaper-1.0.jar\n";
        BridgePlugins bridges = new BridgePlugins(name -> new ByteArrayInputStream(
                (BridgePlugins.INDEX.equals(name) ? index : "not the jar").getBytes(StandardCharsets.UTF_8)));
        Path plugins = dir.resolve("plugins");
        IOException e = assertThrows(IOException.class, () -> bridges.install(BridgePlugins.Bridge.PAPER, plugins));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
        try (var files = Files.list(plugins)) {
            assertEquals(0, files.count(), "neither the jar nor a temporary file stays");
        }
    }

    @Test
    void aMalformedIndexIsRefusedAndAMissingOneMeansNoBridge() throws Exception {
        BridgePlugins traversal = new BridgePlugins(name -> new ByteArrayInputStream(
                ("0".repeat(64) + "  ../VectolynCloudBridgePaper-1.0.jar\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> traversal.fileName(BridgePlugins.Bridge.PAPER));
        BridgePlugins none = new BridgePlugins(name -> null);
        assertTrue(none.fileName(BridgePlugins.Bridge.PAPER).isEmpty());
        assertTrue(none.install(BridgePlugins.Bridge.PAPER, dir.resolve("p")).isEmpty());
    }
}
