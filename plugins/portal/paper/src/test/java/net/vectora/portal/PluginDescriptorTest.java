package net.vectora.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
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
        assertEquals("VectoraPortalBridge", yml.get("name"));
        assertEquals("net.vectora.portal.PortalBridge", yml.get("main"));
        assertEquals("26.3", String.valueOf(yml.get("api-version")));
    }

    @Test
    void versionIsExpandedFromGradle() throws IOException {
        assertEquals(System.getProperty("vectora.expectedVersion"), descriptor().get("version"));
    }

    @Test
    @Tag("requires-java25") // loads JavaPlugin from paper-api 26.3 (class file version 69)
    void mainClassIsAJavaPlugin() throws Exception {
        Class<?> main = Class.forName((String) descriptor().get("main"), false, getClass().getClassLoader());
        assertTrue(JavaPlugin.class.isAssignableFrom(main), "main must extend JavaPlugin");
    }
}
