package net.vectolyn.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class SpigotRestartTest {

    @TempDir
    Path dir;

    private static JsonNode settings(String yaml) {
        return new YAMLMapper().readTree(yaml).get("settings");
    }

    /** The script is empty, so a restart only stops; the watchdog may still end a hung server. */
    private static void assertOff(String yaml) {
        JsonNode settings = settings(yaml);
        assertTrue(settings.get("restart-on-crash").asBoolean(false), yaml);
        assertEquals("", settings.get("restart-script").asString(), yaml);
    }

    @Test
    void turnsTheRestartOffAndKeepsTheRest() throws Exception {
        String before = """
                # comment
                settings:
                  bungeecord: true
                  restart-on-crash: false
                  restart-script: ./start.sh # the default
                  debug: false
                world-settings:
                  default:
                    restart-script: kept
                """;
        String after = SpigotRestart.fix(before);
        assertEquals("""
                # comment
                settings:
                  bungeecord: true
                  restart-on-crash: true
                  restart-script: ''
                  debug: false
                world-settings:
                  default:
                    restart-script: kept
                """, after);
        assertOff(after);
    }

    @Test
    void settingsThatAreRightAlreadyStayByteForByte() throws Exception {
        String yaml = "settings:\r\n    restart-on-crash: true   # stop when hung\r\n    restart-script: \"\"\r\n";
        assertEquals(yaml, SpigotRestart.fix(yaml));
        // Counter-check: one setting wrong, and only its line changes, CRLF kept.
        String wrong = yaml.replace("true   # stop when hung", "false");
        assertEquals("settings:\r\n    restart-on-crash: true\r\n    restart-script: \"\"\r\n", SpigotRestart.fix(wrong));
    }

    @Test
    void missingKeysAreAddedWithTheIndentOfTheBlock() throws Exception {
        // Spigot would add restart-on-crash: true and restart-script: ./start.sh itself.
        String after = SpigotRestart.fix("settings:\n    bungeecord: true\nstats:\n  x: 1\n");
        assertEquals("settings:\n    restart-on-crash: true\n    restart-script: ''\n    bungeecord: true\nstats:\n  x: 1\n",
                after);
        assertOff(after);
        assertTrue(settings(after).get("bungeecord").asBoolean());
    }

    @Test
    void aFileWithoutSettingsGetsTheBlock() throws Exception {
        assertOff(SpigotRestart.fix(""));
        String after = SpigotRestart.fix("config-version: 12");
        assertEquals("config-version: 12\nsettings:\n  restart-on-crash: true\n  restart-script: ''\n", after);
        assertOff(after);
        assertOff(SpigotRestart.fix("settings:\n# nothing yet\n"));
    }

    @Test
    void keysOnlyCountAtTheLevelOfTheBlock() throws Exception {
        String after = SpigotRestart.fix("settings:\n  nested:\n    restart-script: ./start.sh\n  x: 1\n");
        assertOff(after);
        assertEquals("./start.sh", settings(after).get("nested").get("restart-script").asString());
    }

    @Test
    void aValueOverSeveralLinesIsReplacedWhole() throws Exception {
        String after = SpigotRestart.fix("settings:\n  restart-script: |\n    ./start.sh\n\n    --again\n  'restart-on-crash': no\n  x: 1\n");
        assertEquals("settings:\n  restart-script: ''\n  restart-on-crash: true\n  x: 1\n", after);
        assertOff(after);
    }

    @Test
    void anUnreadableLayoutIsRefusedNotGuessed() throws Exception {
        for (String yaml : List.of("settings: {restart-on-crash: true}\n", "settings: &s\n  x: 1\n",
                "{settings: {}}\n", "settings:\n  x: 1\nsettings:\n  y: 2\n",
                // Keys this class cannot read, which a parser would take for one of the two keys or for
                // settings after the fixed lines, so that the old value would win.
                "settings:\n  ? restart-script\n  : ./start.sh\n",
                "settings:\n  &a restart-script: ./start.sh\n",
                "settings:\n  !!str restart-script: ./start.sh\n",
                "settings:\n  \"restart\\x2dscript\": ./start.sh\n",
                "settings:\n  restart-script: ''\n? settings\n: {restart-script: ./start.sh}\n",
                "settings:\n  restart-script: ''\n\"setting\\x73\":\n  restart-script: ./start.sh\n",
                "--- {settings: {restart-script: ./start.sh}}\n",
                "settings:\n  - a\n")) {
            TemplateException e = assertThrows(TemplateException.class, () -> SpigotRestart.fix(yaml), yaml);
            assertTrue(e.getMessage().startsWith("spigot.yml:"), e.getMessage());
        }
        // Counter-check: a byte order mark before a comment, bare document markers, a directive, quoted
        // keys and keys with the same prefix are fine.
        for (String yaml : List.of((char) 0xFEFF + "# c\nsettings:\n  x: 1\n", "---\nsettings:\n  x: 1\n...\n",
                "'settings':\n  'restart-script': x\n  \"debug\": false\n  restart-script-x: y\n")) {
            assertOff(SpigotRestart.fix(yaml));
        }
        assertTrue(SpigotRestart.fix("%YAML 1.1\n---\nsettings:\n  x: 1\n").contains("restart-script: ''"));
    }

    @Test
    void enforceWritesOnlyWhenSomethingChanges() throws Exception {
        assertEquals(Optional.of("spigot.yml"), SpigotRestart.enforce(dir));
        assertOff(Files.readString(dir.resolve("spigot.yml")));
        assertEquals(Optional.empty(), SpigotRestart.enforce(dir));

        Files.writeString(dir.resolve("spigot.yml"), "settings:\n  restart-on-crash: true\n  restart-script: ./start.sh\n");
        assertEquals(Optional.of("spigot.yml"), SpigotRestart.enforce(dir));
        assertOff(Files.readString(dir.resolve("spigot.yml")));
    }

    @Test
    void enforceRefusesAFileThatIsNotUtf8() throws Exception {
        Files.write(dir.resolve("spigot.yml"), new byte[] {'s', ':', (byte) 0xFF, '\n'});
        assertThrows(TemplateException.class, () -> SpigotRestart.enforce(dir));
        Files.delete(dir.resolve("spigot.yml"));
        Files.createDirectory(dir.resolve("spigot.yml"));
        assertThrows(TemplateException.class, () -> SpigotRestart.enforce(dir));
    }

    @Test
    void installTurnsTheRestartOffForSpigotPlatformsOnly() throws Exception {
        Path template = Files.createDirectory(dir.resolve("template"));
        Files.writeString(template.resolve("spigot.yml"),
                "settings:\n  restart-on-crash: true\n  restart-script: ./start.sh\n", UTF_8);
        for (ServerPlatform platform : List.of(ServerPlatform.PAPER, ServerPlatform.PANDASPIGOT)) {
            Path out = dir.resolve(platform.name());
            RenderReport report = TemplateInstaller.install(template, out, platform, PlaceholderValues.none(), false);
            // server.properties: the node always adds enable-command-block=false.
            assertEquals(List.of("server.properties", "spigot.yml"), report.generated(), platform.name());
            assertOff(Files.readString(out.resolve("spigot.yml")));
        }
        // Counter-check: Fabric and Velocity have no spigot.yml of their own; the file stays as it is.
        Path fabric = dir.resolve("fabric");
        RenderReport report = TemplateInstaller.install(template, fabric, ServerPlatform.FABRIC,
                PlaceholderValues.of(Map.of()), false);
        assertEquals(List.of("server.properties"), report.generated());
        assertTrue(Files.readString(fabric.resolve("spigot.yml")).contains("restart-script: ./start.sh"));
    }
}
