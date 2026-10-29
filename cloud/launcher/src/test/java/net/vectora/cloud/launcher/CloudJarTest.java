package net.vectora.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/**
 * Checks the assembled VectoraCloud.jar (task cloudJar), the file the release and the Docker image
 * ship. The build passes its path in the system property {@code vectora.cloudJar}.
 */
class CloudJarTest {

    private static Path cloudJar() {
        String path = System.getProperty("vectora.cloudJar");
        assertNotNull(path, "system property vectora.cloudJar is set by the build");
        Path jar = Path.of(path);
        assertTrue(Files.isRegularFile(jar), "VectoraCloud.jar exists: " + jar);
        return jar;
    }

    @Test
    void fileNameIsStable() {
        // Start scripts, Dockerfile, Pterodactyl egg and release workflow all expect this name.
        assertEquals("VectoraCloud.jar", cloudJar().getFileName().toString());
    }

    @Test
    void manifestStartsTheBootstrapAndAllowsNativeAccess() throws IOException {
        try (JarFile jar = new JarFile(cloudJar().toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "manifest");
            Attributes main = manifest.getMainAttributes();
            assertEquals("net.vectora.cloud.launcher.Bootstrap", main.getValue("Main-Class"));
            assertEquals("ALL-UNNAMED", main.getValue("Enable-Native-Access"));
            assertEquals("VectoraCloud", main.getValue("Implementation-Title"));
            String version = main.getValue("Implementation-Version");
            assertNotNull(version, "Implementation-Version");
            assertTrue(!version.isBlank(), "Implementation-Version is not blank");
        }
    }

    @Test
    void containsTheBootstrapAndTheLibraryIndex() throws IOException {
        try (JarFile jar = new JarFile(cloudJar().toFile())) {
            assertNotNull(jar.getEntry("net/vectora/cloud/launcher/Bootstrap.class"), "Bootstrap.class");
            assertNotNull(jar.getEntry("net/vectora/cloud/launcher/Launcher.class"), "Launcher.class");
            assertNotNull(jar.getEntry("META-INF/vectora/libs.index"), "libs.index");
        }
    }
}
