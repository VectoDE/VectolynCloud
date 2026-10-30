package net.vectolyn.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Renders every real {@code templates/<Server>/} of the repository with test values
 * and checks the result.
 */
class RealTemplatesTest {

    private static final byte[] FLOODGATE_BYTES = "vectolyn-test-16".getBytes(UTF_8);

    /** The backends the cloud knows; which of them are wanted differs per test. */
    private static final List<ProxyBackend> BACKENDS = List.of(
            new ProxyBackend("lobby", "127.0.0.1", 25566, true),
            new ProxyBackend("survival", "127.0.0.1", 25567, true),
            new ProxyBackend("mod", "127.0.0.1", 25568, true),
            new ProxyBackend("creative", "127.0.0.1", 25569, true),
            new ProxyBackend("end", "127.0.0.1", 25570, true),
            new ProxyBackend("minigame", "127.0.0.1", 25571, true));
    private static final List<String> TRY = List.of("lobby", "survival");
    private static final Map<String, List<String>> FORCED_HOSTS = Map.of(
            "creative.example.net", List.of("creative"),
            "survival.example.net", List.of("survival"),
            "minigames.example.net", List.of("minigame", "lobby"));

    @TempDir
    Path out;

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

    /** The template of one server, {@code templates/<server>/}. */
    static Path template(String server) {
        return templatesRoot().resolve(server);
    }

    static List<Path> templates() throws IOException {
        try (Stream<Path> servers = Files.list(templatesRoot())) {
            return servers.filter(Files::isDirectory).sorted().toList();
        }
    }

    static ServerPlatform platformOf(Path template) {
        String server = template.getFileName().toString();
        return switch (server) {
            case "Proxy" -> ServerPlatform.VELOCITY;
            case "Legacy-Proxy" -> ServerPlatform.VELOCITY_LEGACY;
            case "Legacy-Server" -> ServerPlatform.PANDASPIGOT;
            case "Mod-Server" -> ServerPlatform.FABRIC;
            case "Database" -> ServerPlatform.NONE;
            default -> ServerPlatform.PAPER;
        };
    }

    static PlaceholderValues values(Set<String> wanted) {
        Map<String, String> values = new HashMap<>();
        values.put("VECTOLYN_FORWARDING_SECRET", "forwarding-secret-for-tests");
        values.put("VECTOLYN_LEGACY_FORWARDING_SECRET", "legacy-forwarding-secret-for-tests");
        values.put("VECTOLYN_DB_HOST", "127.0.0.1");
        values.put("VECTOLYN_DB_PORT", "3307");
        values.put("VECTOLYN_DB_NAME", "vectolyn_ranks");
        values.put("VECTOLYN_DB_USER", "vectolyn");
        values.put("VECTOLYN_DB_PASSWORD", "db-password-for-tests");
        values.put("VECTOLYN_DB_ROOT_PASSWORD", "root-password-for-tests");
        values.put("VECTOLYN_MANAGEMENT_SECRET", "abcdefghijklmnopqrstuvwxyz0123456789ABCD");
        values.put("VECTOLYN_FLOODGATE_KEY_B64", Base64.getEncoder().encodeToString(FLOODGATE_BYTES));
        values.put("VECTOLYN_SERVER_PORT", "25566");
        values.put("VECTOLYN_SERVER_HOST", "127.0.0.1");
        values.put("VECTOLYN_XMS", "256M");
        values.put("VECTOLYN_XMX", "768M");
        values.put("VECTOLYN_PROXY_BIND", "0.0.0.0");
        values.put("VECTOLYN_PROXY_PORT", "25565");
        values.put("VECTOLYN_BEDROCK_PORT", "19132");
        values.put("VECTOLYN_BEDROCK_NETHERNET_PORT", "19133");
        values.put("VECTOLYN_DB_POOL_SIZE", "4");
        values.put("VECTOLYN_DB_STORAGE_METHOD", "mariadb");
        values.put("VECTOLYN_LEGACY_PROXY_PORT", "25564");
        List<ProxyBackend> backends = BACKENDS.stream()
                .map(b -> new ProxyBackend(b.name(), b.host(), b.port(), wanted.contains(b.name())))
                .toList();
        return PlaceholderValues.of(values)
                .orElse(ProxyServerList.of(backends, TRY, FORCED_HOSTS).placeholderValues());
    }

    private static final Set<String> ALL_BUT_SURVIVAL = Set.of("lobby", "mod", "creative", "end", "minigame");

    @Test
    void everyTemplateRendersCompletelyAndStaysParseable() throws Exception {
        List<Path> templates = templates();
        assertTrue(templates.size() >= 10, "expected the seven servers, minigame and the two legacy templates, found "
                + templates);
        List<String> problems = new ArrayList<>();
        for (Path template : templates) {
            String server = template.getFileName().toString();
            Path runtime = out.resolve(server);
            TemplateInstaller.install(template, runtime, platformOf(template), values(ALL_BUT_SURVIVAL), true);
            try (Stream<Path> files = Files.walk(runtime)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = server + "/" + runtime.relativize(file).toString().replace('\\', '/');
                    String problem = check(file);
                    if (problem != null) {
                        problems.add(name + ": " + problem);
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    private static String check(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, UTF_8);
        if (text.contains("{{")) {
            return "contains {{";
        }
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                new YAMLMapper().readTree(text);
            } else if (lower.endsWith(".json")) {
                JsonMapper.builder().build().readTree(text);
            } else if (lower.endsWith(".properties")) {
                new Properties().load(new StringReader(text));
            } else if (lower.endsWith(".toml")) {
                return TomlCheck.problem(text);
            }
        } catch (RuntimeException e) {
            return "does not parse: " + e.getMessage();
        }
        return null;
    }

    @Test
    void renderedFilesKeepTheirLineEndings() throws Exception {
        for (Path template : templates()) {
            String server = template.getFileName().toString();
            Path runtime = out.resolve(server);
            TemplateInstaller.install(template, runtime, platformOf(template), values(ALL_BUT_SURVIVAL), false);
            try (Stream<Path> files = Files.walk(template)) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    Path target = runtime.resolve(template.relativize(source));
                    if (!Files.exists(target)) {
                        continue;
                    }
                    String before = Files.readString(source);
                    String after = Files.readString(target);
                    assertEquals(before.contains("\r\n"), after.contains("\r\n"), target.toString());
                    assertEquals(before.replace("\r\n", "").contains("\n"), after.replace("\r\n", "").contains("\n"),
                            target.toString());
                }
            }
        }
    }

    @Test
    void generatedFilesLandWhereTheTemplatesExpectThem() throws Exception {
        Map<String, String> keyPaths = Map.of(
                "Proxy", "plugins/floodgate/key.pem",
                "Lobby-Server", "plugins/floodgate/key.pem",
                "Mod-Server", "config/floodgate/key.pem");
        for (Map.Entry<String, String> entry : keyPaths.entrySet()) {
            Path template = template(entry.getKey());
            Path runtime = out.resolve(entry.getKey());
            TemplateInstaller.install(template, runtime, platformOf(template), values(ALL_BUT_SURVIVAL), true);
            Path key = runtime.resolve(entry.getValue());
            assertArrayEquals(FLOODGATE_BYTES, Files.readAllBytes(key), key.toString());
            // The Floodgate config next to it must point at this file name.
            Path floodgateConfig = key.resolveSibling("config.yml");
            assertTrue(Files.readString(floodgateConfig).contains("key-file-name: key.pem"), floodgateConfig.toString());
        }
        assertEquals("forwarding-secret-for-tests", Files.readString(out.resolve("Proxy/forwarding.secret")));
        assertTrue(Files.readString(out.resolve("Proxy/velocity.toml")).contains("forwarding-secret-file = \"forwarding.secret\""));
        assertFalse(Files.exists(out.resolve("Proxy/eula.txt")));
        assertTrue(Files.exists(out.resolve("Lobby-Server/eula.txt")));
        assertTrue(Files.exists(out.resolve("Mod-Server/eula.txt")));
    }

    @Test
    void proxyWithoutSurvivalNeverMentionsSurvival() throws Exception {
        Path template = template("Proxy");
        TemplateInstaller.install(template, out, ServerPlatform.VELOCITY, values(ALL_BUT_SURVIVAL), false);
        String velocity = Files.readString(out.resolve("velocity.toml"));
        assertFalse(velocity.toLowerCase(Locale.ROOT).contains("survival"), velocity);
        assertTrue(velocity.contains("lobby = \"127.0.0.1:25566\""));
    }

    @ParameterizedTest(name = "{0} wanted servers")
    @ValueSource(ints = {1, 3, 6})
    void proxyServerSectionMatchesTheGoldenFile(int count) throws Exception {
        Set<String> wanted = switch (count) {
            case 1 -> Set.of("lobby");
            case 3 -> Set.of("lobby", "survival", "creative");
            case 6 -> Set.of("lobby", "survival", "mod", "creative", "end", "minigame");
            default -> throw new IllegalArgumentException();
        };
        Path template = template("Proxy");
        TemplateInstaller.install(template, out, ServerPlatform.VELOCITY, values(wanted), false);
        String velocity = Files.readString(out.resolve("velocity.toml"));
        int start = velocity.indexOf("[servers]");
        int end = velocity.indexOf("[advanced]");
        assertTrue(start >= 0 && end > start, "velocity.toml lost its [servers] or [advanced] table");
        String actual = velocity.substring(start, end);

        String golden = "golden/velocity-servers-" + count + ".toml";
        try (InputStream in = getClass().getResourceAsStream(golden)) {
            String expected = in == null ? null : new String(in.readAllBytes(), UTF_8);
            if (!actual.equals(expected)) {
                Path dump = Path.of("build", "golden-actual", "velocity-servers-" + count + ".toml");
                Files.createDirectories(dump.getParent());
                Files.writeString(dump, actual);
                if (expected == null) {
                    fail("golden file " + golden + " is missing; the actual output is in " + dump.toAbsolutePath());
                }
                assertEquals(expected, actual, "actual output written to " + dump.toAbsolutePath());
            }
        }
        assertNull(TomlCheck.problem(velocity));
    }

    @Test
    void newTemplatesHaveBothReadmesAndNoneReachesRuntime() throws Exception {
        for (String server : List.of("Minigame-Server", "Legacy-Proxy", "Legacy-Server")) {
            Path template = template(server);
            assertTrue(Files.isRegularFile(template.resolve("README.md")), server);
            assertTrue(Files.isRegularFile(template.resolve("README.de.md")), server);
            Path runtime = out.resolve(server);
            RenderReport report = TemplateInstaller.install(template, runtime, platformOf(template),
                    values(ALL_BUT_SURVIVAL), false);
            assertTrue(report.skipped().containsAll(List.of("README.md", "README.de.md")), report.skipped().toString());
            assertFalse(Files.exists(runtime.resolve("README.md")));
            assertFalse(Files.exists(runtime.resolve("README.de.md")));
        }
    }

    @Test
    void legacyProxyForwardsWithBungeeguardOnItsOwnPort() throws Exception {
        Path template = template("Legacy-Proxy");
        TemplateInstaller.install(template, out, ServerPlatform.VELOCITY_LEGACY, values(Set.of("lobby")), false);
        String velocity = Files.readString(out.resolve("velocity.toml"));
        assertTrue(velocity.contains("player-info-forwarding-mode = \"bungeeguard\""), velocity);
        assertFalse(velocity.contains("player-info-forwarding-mode = \"modern\""));
        assertTrue(velocity.contains("bind = \"0.0.0.0:25564\""));
        assertFalse(velocity.contains(":25565"), "the main proxy's port must not appear");
        assertEquals("legacy-forwarding-secret-for-tests", Files.readString(out.resolve("forwarding.secret")),
                "the legacy proxy gets its own secret, never the modern-forwarding one");
        assertFalse(Files.exists(out.resolve("plugins/floodgate/key.pem")), "no Floodgate on the legacy proxy yet");
        assertNull(TomlCheck.problem(velocity));

        String main = Files.readString(template("Proxy").resolve("velocity.toml"));
        assertTrue(main.contains("player-info-forwarding-mode = \"modern\""), "the main proxy stays on modern");
    }

    @Test
    void legacyServerAcceptsTheLegacyProxyOnly() throws Exception {
        Path template = template("Legacy-Server");
        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.PANDASPIGOT, values(Set.of()),
                false);
        assertEquals(List.of(), report.generated(), "no Floodgate key and, without consent, no eula.txt");
        assertFalse(Files.exists(out.resolve("plugins/floodgate/key.pem")));
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(out.resolve("server.properties"))));
        assertEquals("false", properties.getProperty("online-mode"));
        assertEquals("127.0.0.1", properties.getProperty("server-ip"));
        assertEquals(true, new YAMLMapper().readTree(Files.readString(out.resolve("spigot.yml")))
                .get("settings").get("bungeecord").asBoolean());
        assertEquals(true, new YAMLMapper().readTree(Files.readString(out.resolve("pandaspigot.yml")))
                .get("proxyOnlineMode").asBoolean());
    }

    @Test
    void noSpigotTemplateRestartsItselfOutsideTheNode() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Path template : templates()) {
            ServerPlatform platform = platformOf(template);
            String server = template.getFileName().toString();
            Path runtime = out.resolve(server);
            RenderReport report = TemplateInstaller.install(template, runtime, platform, values(ALL_BUT_SURVIVAL), true);
            if (!platform.readsSpigotYml()) {
                assertFalse(Files.exists(template.resolve("spigot.yml")), server + " has a spigot.yml nobody reads");
                continue;
            }
            // The template itself must be right; the node's enforcement is only the safety net.
            assertTrue(Files.isRegularFile(template.resolve("spigot.yml")), server);
            assertFalse(report.generated().contains("spigot.yml"), server + ": the node had to fix spigot.yml");
            tools.jackson.databind.JsonNode settings = new YAMLMapper()
                    .readTree(Files.readString(runtime.resolve("spigot.yml"))).get("settings");
            // true: with the empty script the watchdog stops a hung PandaSpigot instead of leaving it hung.
            assertTrue(settings.get("restart-on-crash").asBoolean(false), server);
            assertEquals("", settings.get("restart-script").asString(), server);
            checked.add(server);
        }
        assertTrue(checked.containsAll(List.of("Creative-Server", "End-Server", "Legacy-Server", "Lobby-Server",
                "Minigame-Server", "Survival-Server")), checked.toString());
    }

    /**
     * No template ships command blocks turned on: every Minecraft template says
     * {@code enable-command-block=false} itself, so the node never has to fix it, and no other
     * template has a {@code server.properties} at all.
     */
    @Test
    void noTemplateShipsCommandBlocks() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Path template : templates()) {
            String server = template.getFileName().toString();
            Path file = template.resolve("server.properties");
            if (!platformOf(template).readsServerProperties()) {
                assertFalse(Files.exists(file), server + " has a server.properties nobody reads");
                continue;
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
            assertEquals("false", properties.getProperty("enable-command-block"), server);
            RenderReport report = TemplateInstaller.install(template, out.resolve(server), platformOf(template),
                    values(ALL_BUT_SURVIVAL), true);
            assertFalse(report.generated().contains("server.properties"), server + ": the node had to fix it");
            assertEquals(List.of(), report.warnings(), server);
            checked.add(server);
        }
        assertEquals(List.of("Creative-Server", "End-Server", "Legacy-Server", "Lobby-Server", "Minigame-Server",
                "Mod-Server", "Survival-Server"), checked);
    }

    @ParameterizedTest(name = "storage method {0}")
    @ValueSource(strings = {"mariadb", "h2"})
    void luckPermsTakesStorageMethodAndPoolSizeFromTheCloud(String method) throws Exception {
        Map<String, String> db = Map.of("VECTOLYN_DB_STORAGE_METHOD", method, "VECTOLYN_DB_POOL_SIZE", "3");
        PlaceholderValues values = PlaceholderValues.of(db).orElse(values(ALL_BUT_SURVIVAL));
        for (String server : List.of("Lobby-Server", "Survival-Server", "Creative-Server", "End-Server",
                "Minigame-Server", "Proxy")) {
            Path template = template(server);
            Path runtime = out.resolve(method).resolve(server);
            TemplateInstaller.install(template, runtime, platformOf(template), values, false);
            Path config = runtime.resolve(server.equals("Proxy") ? "plugins/luckperms/config.yml"
                    : "plugins/LuckPerms/config.yml");
            tools.jackson.databind.JsonNode yaml = new YAMLMapper().readTree(Files.readString(config));
            assertEquals(method, yaml.get("storage-method").asString(), server);
            assertEquals(3, yaml.get("data").get("pool-settings").get("maximum-pool-size").asInt(), server);
            assertEquals(2, yaml.get("data").get("pool-settings").get("minimum-idle").asInt(), server);
        }
        Path mod = template("Mod-Server");
        Path runtime = out.resolve(method).resolve("Mod-Server");
        TemplateInstaller.install(mod, runtime, ServerPlatform.FABRIC, values, false);
        String conf = Files.readString(runtime.resolve("config/luckperms/luckperms.conf"));
        assertTrue(conf.contains("storage-method = \"" + method + "\""), conf);
        assertTrue(conf.lines().anyMatch(l -> l.strip().equals("maximum-pool-size = 3")), conf);
    }

    @Test
    void gitignoreKeepsEveryRuntimeFolderOut() throws Exception {
        List<String> lines = Files.readAllLines(templatesRoot().getParent().resolve(".gitignore"));
        // A runtime/ folder anywhere (a cloud home put into the checkout by mistake), and the whole
        // Server-Network/ folder that checkouts from before the repository layout change may hold.
        assertTrue(lines.contains("**/runtime/"), lines.toString());
        assertTrue(lines.contains("Server-Network/"), lines.toString());
        // run/: the local cloud home of ./gradlew :cloud:node:runLocal, with secrets and worlds.
        assertTrue(lines.contains("/run/"), lines.toString());
        for (Path template : templates()) {
            // Templates sit directly below templates/; the build maps them to <Server>/template/.
            assertEquals(templatesRoot(), template.getParent(), template.toString());
        }
    }
}
