package net.vectora.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static void assertOff(String yaml) {
        JsonNode settings = settings(yaml);
        assertFalse(settings.get("restart-on-crash").asBoolean(), yaml);
        assertEquals("", settings.get("restart-script").asString(), yaml);
    }

    @Test
    void turnsBothSettingsOffAndKeepsTheRest() throws Exception {
        String before = """
                # comment
                settings:
                  bungeecord: true
                  restart-on-crash: true
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
                  restart-on-crash: false
                  restart-script: ''
                  debug: false
                world-settings:
                  default:
                    restart-script: kept
                """, after);
        assertOff(after);
    }

    @Test
    void settingsThatAreOffAlreadyStayByteForByte() throws Exception {
        String yaml = "settings:\r\n    restart-on-crash: false   # off\r\n    restart-script: \"\"\r\n";
        assertEquals(yaml, SpigotRestart.fix(yaml));
        // Counter-check: one setting on, and only its line changes, CRLF kept.
        String on = yaml.replace("false   # off", "true");
        assertEquals("settings:\r\n    restart-on-crash: false\r\n    restart-script: \"\"\r\n", SpigotRestart.fix(on));
    }

    @Test
    void missingKeysAreAddedWithTheIndentOfTheBlock() throws Exception {
        // Spigot would add restart-on-crash: true and restart-script: ./start.sh itself.
        String after = SpigotRestart.fix("settings:\n    bungeecord: true\nstats:\n  x: 1\n");
        assertEquals("settings:\n    restart-on-crash: false\n    restart-script: ''\n    bungeecord: true\nstats:\n  x: 1\n",
                after);
        assertOff(after);
        assertTrue(settings(after).get("bungeecord").asBoolean());
    }

    @Test
    void aFileWithoutSettingsGetsTheBlock() throws Exception {
        assertOff(SpigotRestart.fix(""));
        String after = SpigotRestart.fix("config-version: 12");
        assertEquals("config-version: 12\nsettings:\n  restart-on-crash: false\n  restart-script: ''\n", after);
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
        String after = SpigotRestart.fix("settings:\n  restart-script: |\n    ./start.sh\n\n    --again\n  'restart-on-crash': yes\n  x: 1\n");
        assertEquals("settings:\n  restart-script: ''\n  restart-on-crash: false\n  x: 1\n", after);
        assertOff(after);
    }

    @Test
    void anUnreadableLayoutIsRefusedNotGuessed() {
        for (String yaml : List.of("settings: {restart-on-crash: true}\n", "settings: &s\n  x: 1\n",
                "{settings: {}}\n", "settings:\n  x: 1\nsettings:\n  y: 2\n")) {
            TemplateException e = assertThrows(TemplateException.class, () -> SpigotRestart.fix(yaml), yaml);
            assertTrue(e.getMessage().startsWith("spigot.yml:"), e.getMessage());
        }
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
            assertEquals(List.of("spigot.yml"), report.generated(), platform.name());
            assertOff(Files.readString(out.resolve("spigot.yml")));
        }
        // Counter-check: Fabric and Velocity have no spigot.yml of their own; the file stays as it is.
        Path fabric = dir.resolve("fabric");
        RenderReport report = TemplateInstaller.install(template, fabric, ServerPlatform.FABRIC,
                PlaceholderValues.of(Map.of()), false);
        assertEquals(List.of(), report.generated());
        assertTrue(Files.readString(fabric.resolve("spigot.yml")).contains("restart-script: ./start.sh"));
    }
}
