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
import java.util.Set;
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

    private Path templates() {
        return home.resolve("local/templates");
    }

    @Test
    void aMissingTemplateIsWrittenAndAnExistingOneIsLeftAlone() throws Exception {
        BuiltInTemplates templates = templates(Map.of(
                "Proxy/template/velocity.toml", "bind = x",
                "Proxy/template/plugins/floodgate/config.yml", "key-file-name: key.pem",
                "Lobby-Server/template/server.properties", "motd=built-in"));
        Files.createDirectories(templates().resolve("Lobby-Server/default"));
        Files.writeString(templates().resolve("Lobby-Server/default/server.properties"), "motd=mine");

        assertEquals(List.of("Proxy"), templates.seed(home, Set.of("Proxy", "Lobby-Server")));

        assertEquals("bind = x", Files.readString(templates().resolve("Proxy/default/velocity.toml")));
        assertEquals("key-file-name: key.pem",
                Files.readString(templates().resolve("Proxy/default/plugins/floodgate/config.yml")));
        assertEquals("motd=mine", Files.readString(templates().resolve("Lobby-Server/default/server.properties")),
                "the operator's template stays as it is");
        assertEquals(List.of("Lobby-Server", "Proxy"), folders(templates()));
        assertEquals(List.of("default"), folders(templates().resolve("Proxy")), "no staging folder is left behind");
        assertEquals(List.of(), templates.seed(home, Set.of("Proxy", "Lobby-Server")), "a second start writes nothing");
    }

    @Test
    void onlyTheWantedTemplatesAreWritten() throws Exception {
        BuiltInTemplates templates = templates(Map.of(
                "Proxy/template/velocity.toml", "bind = x",
                "Survival-Server/template/server.properties", "motd=s"));

        assertEquals(List.of("Proxy"), templates.seed(home, Set.of("Proxy", "My-Own-Template")));

        assertEquals(List.of("Proxy"), folders(templates()), "an unwanted server gets no folder at all");
        assertEquals(List.of("Survival-Server"), templates.seed(home, Set.of("Proxy", "Survival-Server")),
                "switching a server on later writes its template then");
        assertEquals(Set.of("Proxy", "Survival-Server"), templates.names());
    }

    @Test
    void anUnchangedCopyIsRecognisedAndAChangedOneIsNot() throws Exception {
        BuiltInTemplates templates = templates(Map.of(
                "Proxy/template/velocity.toml", "bind = x",
                "Proxy/template/plugins/a.yml", "a"));
        templates.seed(home, Set.of("Proxy"));
        Path copy = templates().resolve("Proxy/default");

        assertTrue(templates.isUnchangedCopy("Proxy", copy));
        assertFalse(templates.isUnchangedCopy("Lobby-Server", copy), "another template");
        Files.writeString(copy.resolve("extra.txt"), "mine");
        assertFalse(templates.isUnchangedCopy("Proxy", copy), "an extra file");
        Files.delete(copy.resolve("extra.txt"));
        Files.writeString(copy.resolve("velocity.toml"), "bind = y");
        assertFalse(templates.isUnchangedCopy("Proxy", copy), "an edited file");
    }

    @Test
    void aFileThatDoesNotMatchTheIndexStopsTheFolderAndLeavesNothingBehind() throws Exception {
        BuiltInTemplates templates = templates(Map.of("Proxy/template/velocity.toml", "bind = x"));
        resources.put("Proxy/template/velocity.toml", "tampered".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class, () -> templates.seed(home, Set.of("Proxy")));

        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
        assertFalse(Files.exists(templates().resolve("Proxy/default")));
        try (Stream<Path> left = Files.list(templates().resolve("Proxy"))) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void anIndexLineThatLeavesItsFolderIsRefused() throws Exception {
        for (String bad : List.of("Proxy/template/../../evil.txt", "Proxy/other/file.txt", "../template/x",
                "Proxy/template/.hidden", "Proxy/template/a\\b")) {
            resources.clear();
            BuiltInTemplates templates = templates(Map.of(bad, "x"));
            IOException error = assertThrows(IOException.class, () -> templates.seed(home, Set.of("Proxy")), bad);
            assertTrue(error.getMessage().contains("malformed"), error.getMessage());
        }
        // Counter-check: a nested, well-formed path is accepted.
        resources.clear();
        assertEquals(List.of("Proxy"), templates(Map.of("Proxy/template/a/b-c_d.yml", "x")).seed(home, Set.of("Proxy")));
    }

    @Test
    void theNodeCarriesATemplateForEveryServerOfTheNetwork() throws Exception {
        BuiltInTemplates builtIn = BuiltInTemplates.fromClassPath();
        List<String> expected = new ArrayList<>();
        for (ServerKind kind : ServerKind.values()) {
            expected.add(kind.templateFolder());
        }
        expected.add("Legacy-Proxy");
        expected.sort(null);
        assertEquals(expected, List.copyOf(builtIn.names()));

        List<String> written = builtIn.seed(home, builtIn.names());

        assertEquals(expected, written);
        assertTrue(Files.readString(templates().resolve("Proxy/default/velocity.toml")).contains("{{VECTORA_PROXY_SERVERS}}"));
        assertTrue(Files.isDirectory(templates().resolve("Lobby-Server/default/plugins/floodgate")));
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
