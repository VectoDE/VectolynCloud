package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.vectora.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordFileTest {

    private static PasswordFile.Result parse(String content) {
        return PasswordFile.parse(content.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3cret-Pw", "s3cret-Pw\n", "s3cret-Pw\r\n"})
    void onePasswordLineWithOrWithoutBreak(String content) {
        PasswordFile.Result result = parse(content);
        assertNull(result.problem());
        assertEquals("s3cret-Pw", result.secret().reveal());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "two\nlines", "pw\n\n", " pw", "pw ", "pw\r", "a\u0000b"})
    void anythingElseIsRefused(String content) {
        assertSame(SetupMessages.FILE_PASSWORD_FORM, parse(content).problem());
    }

    @Test
    void invalidUtf8AndOversizedFilesAreRefused() {
        assertSame(SetupMessages.FILE_PASSWORD_FORM, PasswordFile.parse(new byte[] {'a', (byte) 0xC3}).problem());
        assertSame(SetupMessages.FILE_PASSWORD_FORM, PasswordFile.parse(new byte[PasswordFile.MAX_BYTES + 1]).problem());
        byte[] largest = "x".repeat(PasswordFile.MAX_BYTES).getBytes(StandardCharsets.UTF_8);
        assertNull(PasswordFile.parse(largest).problem(), "the limit itself is fine");
    }

    @Test
    void readResolvesAgainstTheHomeAndReportsMissingFiles(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("pw"), "from-file\n");
        assertEquals("from-file", PasswordFile.read(home, "pw").secret().reveal());
        assertEquals("from-file", PasswordFile.read(home, home.resolve("pw").toString()).secret().reveal());
        assertSame(SetupMessages.FILE_PASSWORD_UNREADABLE, PasswordFile.read(home, "absent").problem());
        assertSame(SetupMessages.FILE_PASSWORD_UNREADABLE, PasswordFile.read(home, ".").problem());
    }

    @Test
    void onlyRegularFilesAreRead(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("folder"));
        assertSame(SetupMessages.FILE_PASSWORD_UNREADABLE, PasswordFile.read(home, "folder").problem());
        Path fifo = home.resolve("fifo");
        if (Files.isDirectory(Path.of("/dev")) && new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0) {
            // Would block forever if it were opened.
            assertSame(SetupMessages.FILE_PASSWORD_UNREADABLE, PasswordFile.read(home, "fifo").problem());
        }
        if (Files.exists(Path.of("/dev/zero"))) {
            assertSame(SetupMessages.FILE_PASSWORD_UNREADABLE, PasswordFile.read(home, "/dev/zero").problem());
        }
        // Counter-check: a regular file next to them is read.
        Files.writeString(home.resolve("pw"), "regular-Pw\n");
        assertEquals("regular-Pw", PasswordFile.read(home, "pw").secret().reveal());
    }

    @Test
    void resultHoldsExactlyOneAndNeverPrintsTheSecret() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordFile.Result(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordFile.Result(Secret.of("x"), SetupMessages.FILE_PASSWORD_FORM));
        assertFalse(parse("visible-pw").toString().contains("visible-pw"));
    }
}
