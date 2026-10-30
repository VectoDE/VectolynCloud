package net.vectora.cloud.template;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Fuzz target for {@link CommandBlockSetting}: a {@code server.properties} of a template is
 * untrusted input. Whatever the file says, {@code fix} never throws, and every file the server
 * could read reads {@code enable-command-block=false} afterwards; fixing twice changes nothing.
 * In a normal test run it executes in regression mode; the nightly workflow fuzzes it on Linux.
 */
class CommandBlockSettingFuzzTest {

    @FuzzTest
    void everyReadableFileEndsWithCommandBlocksOff(byte @NotNull [] input) throws IOException {
        // As enforce() reads the file: one character per byte.
        String text = new String(input, StandardCharsets.ISO_8859_1);
        CommandBlockSetting.Fixed fixed = CommandBlockSetting.fix(text);
        if (readable(text)) {
            Properties after = new Properties();
            after.load(new StringReader(fixed.text()));
            assertEquals("false", after.getProperty(CommandBlockSetting.KEY), fixed.text());
            assertEquals(fixed.text(), CommandBlockSetting.fix(fixed.text()).text(), "a second fix changes nothing");
        }
    }

    private static boolean readable(String text) {
        try {
            new Properties().load(new StringReader(text));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
