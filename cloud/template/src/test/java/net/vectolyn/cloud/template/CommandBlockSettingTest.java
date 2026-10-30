package net.vectolyn.cloud.template;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandBlockSettingTest {

    @TempDir
    Path dir;

    /** The value the server reads. */
    private static String read(String text) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        return properties.getProperty(CommandBlockSetting.KEY);
    }

    @Test
    void trueIsOverwrittenAndTheRestStays() throws Exception {
        CommandBlockSetting.Fixed fixed = CommandBlockSetting.fix("#Minecraft server properties\n"
                + "difficulty=easy\nenable-command-block=true\nmotd=A Minecraft Server\n");
        assertEquals("#Minecraft server properties\ndifficulty=easy\nenable-command-block=false\nmotd=A Minecraft Server\n",
                fixed.text());
        assertTrue(fixed.overwritten());
    }

    @Test
    void falseStaysAsItIs() {
        String text = "difficulty=easy\r\nenable-command-block=false\r\n";
        CommandBlockSetting.Fixed fixed = CommandBlockSetting.fix(text);
        assertEquals(text, fixed.text());
        assertFalse(fixed.overwritten());
    }

    @Test
    void everyOtherLineKeepsItsLineBreak() {
        assertEquals("a=1\r\nenable-command-block=false\nb=2\rc=3", CommandBlockSetting.fix(
                "a=1\r\nenable-command-block=true\nb=2\rc=3").text());
        assertEquals("a=1\r\nb=2\r\nenable-command-block=false\r\n", CommandBlockSetting.fix("a=1\r\nb=2").text());
        assertEquals("a=1\nenable-command-block=false", CommandBlockSetting.fix("a=1\nenable-command-block=false").text(),
                "a right last line without a line break stays");
    }

    @Test
    void aMissingKeyIsAddedAtTheEnd() {
        assertEquals("difficulty=easy\nenable-command-block=false\n", CommandBlockSetting.fix("difficulty=easy\n").text());
        assertEquals("difficulty=easy\nenable-command-block=false\n", CommandBlockSetting.fix("difficulty=easy").text());
        assertEquals("enable-command-block=false\n", CommandBlockSetting.fix("").text());
        assertFalse(CommandBlockSetting.fix("difficulty=easy\n").overwritten());
    }

    /** Every way the server would read the key on (or anything but false) is replaced. */
    @ParameterizedTest
    @ValueSource(strings = {
        "enable-command-block=TRUE",
        "  enable-command-block = true",
        "enable-command-block:true",
        "enable-command-block true",
        "enable\\-command\\-block=true",
        "enable-command-block=tr\\\n    ue",
        "enable-command-bl\\\n  ock=true",
        "enable-command-block=",
        "enable-command-block=yes",
        "\\u0065nable-command-block=true",
    })
    void everySpellingTheServerReadsIsReplaced(String line) throws Exception {
        String text = "a=1\n" + line + "\nb=2\nenable-command-block=true\n";
        CommandBlockSetting.Fixed fixed = CommandBlockSetting.fix(text);
        assertEquals("false", read(fixed.text()), fixed.text());
        assertEquals("a=1\nenable-command-block=false\nb=2\n", fixed.text(), "one line in place of the first");
        assertTrue(fixed.overwritten());
    }

    @Test
    void commentsAndOtherKeysAreNoOccurrence() throws Exception {
        String text = "# enable-command-block=true\n! enable-command-block=true\nmy-enable-command-block=true\n"
                + "x=\\\n#enable-command-block=true\n";
        CommandBlockSetting.Fixed fixed = CommandBlockSetting.fix(text);
        assertEquals(text + "enable-command-block=false\n", fixed.text());
        assertFalse(fixed.overwritten());
        assertEquals("false", read(fixed.text()));
    }

    @Test
    void enforceKeepsTheOtherBytesAndReportsWhatItDid() throws Exception {
        byte[] umlaut = "motd=Grüße\n".getBytes(UTF_8);
        byte[] before = concat("enable-command-block=true\n".getBytes(UTF_8), umlaut);
        Files.write(dir.resolve("server.properties"), before);
        assertEquals(new CommandBlockSetting.Outcome(true, true), CommandBlockSetting.enforce(dir));
        assertArrayEquals(concat("enable-command-block=false\n".getBytes(UTF_8), umlaut),
                Files.readAllBytes(dir.resolve("server.properties")), "the UTF-8 bytes of the motd are untouched");
        assertEquals(new CommandBlockSetting.Outcome(false, false), CommandBlockSetting.enforce(dir), "already right");
    }

    @Test
    void enforceCreatesAMissingFileAndRefusesUnsafeOrUnreadableOnes() throws Exception {
        assertEquals(new CommandBlockSetting.Outcome(true, false), CommandBlockSetting.enforce(dir));
        assertEquals("enable-command-block=false\n", Files.readString(dir.resolve("server.properties"), ISO_8859_1));

        Files.writeString(dir.resolve("server.properties"), "motd=\\u12\n", ISO_8859_1);
        assertThrows(TemplateException.class, () -> CommandBlockSetting.enforce(dir), "a malformed escape");
        Files.delete(dir.resolve("server.properties"));
        Files.createDirectory(dir.resolve("server.properties"));
        assertThrows(TemplateException.class, () -> CommandBlockSetting.enforce(dir));
    }

    @Test
    void installForcesItForEveryMinecraftPlatformAndWarns() throws Exception {
        Path template = Files.createDirectory(dir.resolve("template"));
        Files.writeString(template.resolve("server.properties"), "enable-command-block=true\n", UTF_8);
        for (ServerPlatform platform : List.of(ServerPlatform.PAPER, ServerPlatform.PANDASPIGOT, ServerPlatform.FABRIC)) {
            Path out = dir.resolve(platform.name());
            RenderReport report = TemplateInstaller.install(template, out, platform, PlaceholderValues.of(Map.of()), false);
            assertTrue(report.generated().contains("server.properties"), platform.name());
            assertEquals(List.of(TemplateInstaller.COMMAND_BLOCK_WARNING), report.warnings(), platform.name());
            assertEquals("false", read(Files.readString(out.resolve("server.properties"))), platform.name());
        }
        // Counter-check: the proxies and the database read no server.properties.
        for (ServerPlatform platform : List.of(ServerPlatform.VELOCITY_LEGACY, ServerPlatform.NONE)) {
            assertFalse(platform.readsServerProperties(), platform.name());
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
