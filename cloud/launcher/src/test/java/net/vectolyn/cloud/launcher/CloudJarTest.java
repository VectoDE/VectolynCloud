package net.vectolyn.cloud.launcher;

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
 * Checks the assembled VectolynCloud.jar (task cloudJar), the file the release and the Docker image
 * ship. The build passes its path in the system property {@code vectolyn.cloudJar}.
 */
class CloudJarTest {

    private static Path cloudJar() {
        String path = System.getProperty("vectolyn.cloudJar");
        assertNotNull(path, "system property vectolyn.cloudJar is set by the build");
        Path jar = Path.of(path);
        assertTrue(Files.isRegularFile(jar), "VectolynCloud.jar exists: " + jar);
        return jar;
    }

    @Test
    void fileNameIsStable() {
        // Start scripts, Dockerfile, Pterodactyl egg and release workflow all expect this name.
        assertEquals("VectolynCloud.jar", cloudJar().getFileName().toString());
    }

    @Test
    void manifestStartsTheBootstrapAndAllowsNativeAccess() throws IOException {
        try (JarFile jar = new JarFile(cloudJar().toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "manifest");
            Attributes main = manifest.getMainAttributes();
            assertEquals("net.vectolyn.cloud.launcher.Bootstrap", main.getValue("Main-Class"));
            assertEquals("ALL-UNNAMED", main.getValue("Enable-Native-Access"));
            assertEquals("VectolynCloud", main.getValue("Implementation-Title"));
            String version = main.getValue("Implementation-Version");
            assertNotNull(version, "Implementation-Version");
            assertTrue(!version.isBlank(), "Implementation-Version is not blank");
        }
    }

    @Test
    void containsTheBootstrapAndTheLibraryIndex() throws IOException {
        try (JarFile jar = new JarFile(cloudJar().toFile())) {
            assertNotNull(jar.getEntry("net/vectolyn/cloud/launcher/Bootstrap.class"), "Bootstrap.class");
            assertNotNull(jar.getEntry("net/vectolyn/cloud/launcher/Launcher.class"), "Launcher.class");
            assertNotNull(jar.getEntry("META-INF/vectolyn/libs.index"), "libs.index");
        }
    }
}
