package net.vectora.cloud.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PluginDescriptorTest {

    private static Map<String, Object> descriptor() throws IOException {
        try (InputStream in = PluginDescriptorTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml must be on the classpath");
            return new Yaml().load(in);
        }
    }

    @Test
    void hasRequiredFields() throws IOException {
        Map<String, Object> yml = descriptor();
        assertEquals("VectoraCloudBridge", yml.get("name"));
        assertEquals("net.vectora.cloud.bridge.legacy.LegacyBridgePlugin", yml.get("main"));
        assertEquals("STARTUP", yml.get("load"), "the token check must be enabled before the worlds load");
        assertNull(yml.get("api-version"), "1.8.8 has no api-version");
    }

    @Test
    void versionIsExpandedFromGradle() throws IOException {
        assertEquals(System.getProperty("vectora.expectedVersion"), descriptor().get("version"));
    }

    @Test
    void mainClassIsAJavaPlugin() throws Exception {
        // The PandaSpigot API has Java 8 class files, so this runs on Java 21 as well.
        Class<?> main = Class.forName((String) descriptor().get("main"), false, getClass().getClassLoader());
        assertTrue(JavaPlugin.class.isAssignableFrom(main), "main must extend JavaPlugin");
        assertTrue(Listener.class.isAssignableFrom(HandshakeGuard.class));
    }
}
