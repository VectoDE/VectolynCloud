package net.vectora.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeStartTest {

    @Test
    void theLauncherFindsAPublicStaticStartMethod() throws Exception {
        Method start = Class.forName("net.vectora.cloud.node.NodeMain").getMethod("start", Map.class);
        assertTrue(Modifier.isPublic(start.getModifiers()) && Modifier.isStatic(start.getModifiers()));
        assertEquals(void.class, start.getReturnType());
    }

    @Test
    void settingsAreParsedAndChecked(@TempDir Path dir) {
        String home = dir.toAbsolutePath().toString();
        NodeSettings settings = NodeSettings.parse(Map.of("home", home, "language", "de", "non-interactive", "true",
                "launcher.version", "2.0.0", "platform.kind", "pterodactyl"));
        assertEquals(dir.toAbsolutePath().normalize(), settings.home());
        assertEquals("de", settings.language());
        assertTrue(settings.nonInteractive());
        assertEquals(Optional.empty(), settings.setupFile());
        assertEquals(Map.of("kind", "pterodactyl"), settings.platform());
        assertEquals("en", NodeSettings.parse(Map.of("home", home)).language());

        for (Map<String, String> bad : java.util.List.of(Map.<String, String>of(), Map.of("home", " "),
                Map.of("home", "relative/path"), Map.of("home", home, "language", "fr"),
                Map.of("home", home, "root-allowed", "yes"))) {
            assertThrows(IllegalArgumentException.class, () -> NodeSettings.parse(bad), bad.toString());
        }
    }

    @Test
    void anIncompleteSetupCreatesSecretsButStartsNoServer(@TempDir Path dir) throws Exception {
        NodeSettings settings = NodeSettings.parse(Map.of("home", dir.toAbsolutePath().toString()));
        CloudNode node = CloudNode.open(settings, options -> options.noConsole()
                .network(network -> network.withAddress("127.0.0.1", 0)));
        try {
            assertTrue(Files.isRegularFile(dir.resolve("secrets").resolve("vectora.env")), "secrets generated");
            node.start();
            assertTrue(node.services().services().isEmpty(), "no service without setup and EULA");
        } finally {
            node.close();
        }
        node.close(); // closing twice is harmless
    }
}
