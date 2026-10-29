package net.vectora.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.vectora.cloud.config.model.ServerKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltInTemplatesTest {

    @TempDir
    Path home;

    private final Map<String, byte[]> resources = new LinkedHashMap<>();

    private BuiltInTemplates templates(Map<String, String> files) throws Exception {
        StringBuilder index = new StringBuilder("# test\n");
        for (Map.Entry<String, String> file : files.entrySet()) {
            byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
            resources.put(file.getKey(), bytes);
            index.append(sha256(bytes)).append("  ").append(file.getKey()).append('\n');
        }
        resources.put(BuiltInTemplates.INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
        return new BuiltInTemplates(name -> {
            byte[] bytes = resources.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
    }

    @Test
    void aMissingTemplateIsWrittenAndAnExistingOneIsLeftAlone() throws Exception {
        BuiltInTemplates templates = templates(Map.of(
                "Proxy/template/velocity.toml", "bind = x",
                "Proxy/template/plugins/floodgate/config.yml", "key-file-name: key.pem",
                "Lobby-Server/template/server.properties", "motd=built-in"));
        Files.createDirectories(home.resolve("Lobby-Server/template"));
        Files.writeString(home.resolve("Lobby-Server/template/server.properties"), "motd=mine");

        assertEquals(List.of("Proxy"), templates.seed(home));

        assertEquals("bind = x", Files.readString(home.resolve("Proxy/template/velocity.toml")));
        assertEquals("key-file-name: key.pem",
                Files.readString(home.resolve("Proxy/template/plugins/floodgate/config.yml")));
        assertEquals("motd=mine", Files.readString(home.resolve("Lobby-Server/template/server.properties")),
                "the operator's template stays as it is");
        assertEquals(List.of("Lobby-Server", "Proxy"), folders(home), "no staging folder is left behind");
        assertEquals(List.of(), templates.seed(home), "a second start writes nothing");
    }

    @Test
    void aFileThatDoesNotMatchTheIndexStopsTheFolderAndLeavesNothingBehind() throws Exception {
        BuiltInTemplates templates = templates(Map.of("Proxy/template/velocity.toml", "bind = x"));
        resources.put("Proxy/template/velocity.toml", "tampered".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class, () -> templates.seed(home));

        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
        assertFalse(Files.exists(home.resolve("Proxy/template")));
        try (Stream<Path> left = Files.list(home.resolve("Proxy"))) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void anIndexLineThatLeavesItsFolderIsRefused() throws Exception {
        for (String bad : List.of("Proxy/template/../../evil.txt", "Proxy/other/file.txt", "../template/x",
                "Proxy/template/.hidden", "Proxy/template/a\\b")) {
            resources.clear();
            BuiltInTemplates templates = templates(Map.of(bad, "x"));
            IOException error = assertThrows(IOException.class, () -> templates.seed(home), bad);
            assertTrue(error.getMessage().contains("malformed"), error.getMessage());
        }
        // Counter-check: a nested, well-formed path is accepted.
        resources.clear();
        assertEquals(List.of("Proxy"), templates(Map.of("Proxy/template/a/b-c_d.yml", "x")).seed(home));
    }

    @Test
    void theNodeCarriesATemplateForEveryServerOfTheNetwork() throws Exception {
        List<String> written = BuiltInTemplates.fromClassPath().seed(home);

        List<String> expected = new ArrayList<>();
        for (ServerKind kind : ServerKind.values()) {
            expected.add(kind.templateFolder());
        }
        expected.add("Legacy-Proxy");
        expected.sort(null);
        assertEquals(expected, written);
        assertTrue(Files.readString(home.resolve("Proxy/template/velocity.toml")).contains("{{VECTORA_PROXY_SERVERS}}"));
        assertTrue(Files.isDirectory(home.resolve("Lobby-Server/template/plugins/floodgate")));
        try (Stream<Path> files = Files.walk(home)) {
            assertFalse(files.anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".jar") || name.equals("key.pem") || name.endsWith(".secret");
            }), "no jar and no secret travels in the node");
        }
    }

    private static List<String> folders(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
