package net.vectora.cloud.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.EnvFileParser;
import net.vectora.cloud.template.PlaceholderValues;
import net.vectora.cloud.template.ServerPlatform;
import net.vectora.cloud.template.TemplateException;
import net.vectora.cloud.template.TemplateInstaller;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders every real {@code templates/<Server>/} of the repository the way the node does (secrets
 * scoped by {@link ServiceSecrets}) into a temporary folder, then searches every written byte for
 * each secret. Reads only the template folders, never a {@code runtime/} or {@code secrets/}
 * folder; the secrets are made-up test values.
 */
class RealTemplateSecretsTest {

    private static final String MODERN = "modernForwardingTestValue0001";
    private static final String LEGACY = "legacyForwardingTestValue0002";
    private static final String DB = "databasePasswordTestValue0003";
    private static final String ROOT = "databaseRootPasswordTestValue04";
    private static final String MANAGEMENT = "managementSecretTestValue00005";
    private static final byte[] FLOODGATE_KEY = "floodgate-key-16".getBytes(StandardCharsets.US_ASCII);

    /** Folder to platform, as the node picks it from the group's software and template. */
    private static final Map<String, ServerPlatform> PLATFORMS = Map.of(
            "Proxy", ServerPlatform.VELOCITY,
            "Legacy-Proxy", ServerPlatform.VELOCITY_LEGACY,
            "Legacy-Server", ServerPlatform.PANDASPIGOT,
            "Mod-Server", ServerPlatform.FABRIC,
            "Database", ServerPlatform.NONE);

    @TempDir
    Path out;

    @Test
    void theRootPasswordReachesNoTemplateAndEachSecretOnlyItsPlatforms() throws Exception {
        EnvFile env = EnvFileParser.parse(String.join("\n",
                ServiceSecrets.FORWARDING + "=" + MODERN,
                ServiceSecrets.LEGACY_FORWARDING + "=" + LEGACY,
                ServiceSecrets.DB_PASSWORD + "=" + DB,
                ServiceSecrets.DB_ROOT_PASSWORD + "=" + ROOT,
                ServiceSecrets.MANAGEMENT + "=" + MANAGEMENT,
                ServiceSecrets.FLOODGATE + "=" + Base64.getEncoder().encodeToString(FLOODGATE_KEY),
                ""));
        List<Path> templates = templates();
        assertEquals(10, templates.size(), "every server folder of templates/: " + templates);

        Map<String, Map<String, Boolean>> seen = new TreeMap<>();
        for (Path template : templates) {
            String folder = template.getFileName().toString();
            ServerPlatform platform = PLATFORMS.getOrDefault(folder, ServerPlatform.PAPER);
            Path runtime = out.resolve(folder);
            PlaceholderValues values = values(folder, platform, env);
            TemplateInstaller.install(template, runtime, platform, values, true);
            String all = readAll(runtime);
            byte[] bytes = all.getBytes(StandardCharsets.ISO_8859_1);
            Map<String, Boolean> found = new TreeMap<>();
            found.put("root", all.contains(ROOT));
            found.put("modern", all.contains(MODERN));
            found.put("legacy", all.contains(LEGACY));
            found.put("db", all.contains(DB));
            found.put("management", all.contains(MANAGEMENT));
            found.put("floodgate", indexOf(bytes, FLOODGATE_KEY) >= 0);
            seen.put(folder, found);
        }

        for (Map.Entry<String, Map<String, Boolean>> entry : seen.entrySet()) {
            assertFalse(entry.getValue().get("root"), "the root password reached " + entry.getKey());
        }
        // Legacy part: its own secret only on the legacy proxy, nothing of the main network.
        assertEquals(Map.of("root", false, "modern", false, "legacy", true, "db", false, "management", false,
                "floodgate", false), seen.get("Legacy-Proxy"));
        assertEquals(Map.of("root", false, "modern", false, "legacy", false, "db", false, "management", false,
                "floodgate", false), seen.get("Legacy-Server"), "the 1.8.8 server gets its secret by environment only");
        // Main proxy: forwarding.secret, Floodgate key; no management secret, no legacy secret.
        assertEquals(Map.of("root", false, "modern", true, "legacy", false, "db", true, "management", false,
                "floodgate", true), seen.get("Proxy"));
        // Counter-check: the modern backends really receive what they need.
        for (String backend : List.of("Lobby-Server", "Survival-Server", "Creative-Server", "End-Server",
                "Minigame-Server", "Mod-Server")) {
            assertEquals(Map.of("root", false, "modern", true, "legacy", false, "db", true, "management", true,
                    "floodgate", true), seen.get(backend), backend);
        }
        assertEquals(Map.of("root", false, "modern", false, "legacy", false, "db", true, "management", false,
                "floodgate", false), seen.get("Database"));
    }

    @Test
    void aTemplateThatAsksForASecretItMayNotSeeFailsToRender() throws Exception {
        EnvFile env = EnvFileParser.parse(String.join("\n",
                ServiceSecrets.FORWARDING + "=" + MODERN,
                ServiceSecrets.LEGACY_FORWARDING + "=" + LEGACY,
                ServiceSecrets.DB_PASSWORD + "=" + DB,
                ServiceSecrets.DB_ROOT_PASSWORD + "=" + ROOT,
                ServiceSecrets.MANAGEMENT + "=" + MANAGEMENT,
                ServiceSecrets.FLOODGATE + "=" + Base64.getEncoder().encodeToString(FLOODGATE_KEY),
                ""));
        Path template = out.resolve("Evil/template");
        Files.createDirectories(template);
        Files.writeString(template.resolve("server.properties"), "root={{VECTORA_DB_ROOT_PASSWORD}}\n");
        TemplateException root = assertThrows(TemplateException.class, () -> TemplateInstaller.install(template,
                out.resolve("evil-runtime"), ServerPlatform.PAPER, values("Lobby-Server", ServerPlatform.PAPER, env), true));
        assertTrue(root.getMessage().contains("VECTORA_DB_ROOT_PASSWORD"), root.getMessage());
        assertFalse(root.getMessage().contains(ROOT));
        assertFalse(Files.exists(out.resolve("evil-runtime/server.properties")), "nothing rendered");

        // The legacy proxy asking for the modern secret, or a 1.8.8 server for the legacy one.
        Files.writeString(template.resolve("server.properties"), "secret={{VECTORA_FORWARDING_SECRET}}\n");
        assertThrows(TemplateException.class, () -> TemplateInstaller.install(template, out.resolve("evil-2"),
                ServerPlatform.VELOCITY_LEGACY, values("Legacy-Proxy", ServerPlatform.VELOCITY_LEGACY, env), true));
        Files.writeString(template.resolve("server.properties"), "secret={{VECTORA_LEGACY_FORWARDING_SECRET}}\n");
        assertThrows(TemplateException.class, () -> TemplateInstaller.install(template, out.resolve("evil-3"),
                ServerPlatform.PANDASPIGOT, values("Legacy-Server", ServerPlatform.PANDASPIGOT, env), true));
        // Counter-check: the same file renders where the secret belongs.
        TemplateInstaller.install(template, out.resolve("fine"), ServerPlatform.VELOCITY_LEGACY,
                values("Legacy-Proxy", ServerPlatform.VELOCITY_LEGACY, env), true);
        assertEquals("secret=" + LEGACY + "\n", Files.readString(out.resolve("fine/server.properties")));
    }

    private static PlaceholderValues values(String folder, ServerPlatform platform, EnvFile env) throws ServiceException {
        ServerSoftware software = switch (platform) {
            case VELOCITY, VELOCITY_LEGACY -> ServerSoftware.VELOCITY;
            case PANDASPIGOT -> ServerSoftware.PANDASPIGOT;
            case FABRIC -> ServerSoftware.FABRIC;
            case PAPER, NONE -> ServerSoftware.PAPER;
        };
        GroupConfig group = new GroupConfig(GroupConfig.SCHEMA_VERSION, folder, software,
                software == ServerSoftware.PANDASPIGOT ? "1.8.8" : "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("1G")), List.of(), CombatSystem.MODERN);
        String name = folder.toLowerCase(java.util.Locale.ROOT);
        int port = platform == ServerPlatform.VELOCITY ? 25565 : platform == ServerPlatform.VELOCITY_LEGACY ? 25564 : 30001;
        // The real template folder, read only: whether it ships Floodgate decides about the key.
        Path templates = templatesRoot();
        ServiceSpec spec = new ServiceSpec(new ServiceId(name + "-1"), name, name, 1, group, TaskMode.STATIC,
                ServicePlanner.kindOf(group), templates.resolve(folder),
                Path.of("home").toAbsolutePath().resolve(folder).resolve("runtime"), port);
        Map<String, String> database = new HashMap<>();
        database.put("VECTORA_DB_POOL_SIZE", "4");
        database.put("VECTORA_DB_STORAGE_METHOD", "mariadb");
        return ServicePlaceholders.build(spec, platform, ConfigSnapshot.defaults(), NodeConfig.defaults(), env,
                List.of(), List.of(), List.of(snapshot -> database));
    }

    private static String readAll(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            all.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).append('\n');
        }
        return all.toString();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static Path templatesRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isDirectory(dir.resolve("templates"))) {
                return dir.resolve("templates");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("templates/ not found above " + Path.of("").toAbsolutePath());
    }

    static List<Path> templates() throws IOException {
        try (Stream<Path> servers = Files.list(templatesRoot())) {
            return servers.filter(Files::isDirectory).sorted().toList();
        }
    }
}
