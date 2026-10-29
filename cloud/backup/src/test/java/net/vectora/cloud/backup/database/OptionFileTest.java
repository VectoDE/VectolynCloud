package net.vectora.cloud.backup.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OptionFileTest {

    @TempDir
    Path work;

    @Test
    void fileIsOwnerOnlyHoldsTheCredentialsAndIsDeletedOnClose() throws Exception {
        Path file;
        try (OptionFile option = OptionFile.create(work, "root", Secret.of("S3cret.pass_word-123"), "127.0.0.1", 3307)) {
            file = option.file();
            assertTrue(OwnerOnlyPermissions.isOwnerOnly(file));
            assertTrue(OwnerOnlyPermissions.isOwnerOnly(file.getParent()));
            assertEquals("""
                    [client]
                    user=root
                    password="S3cret.pass_word-123"
                    host=127.0.0.1
                    port=3307
                    protocol=TCP
                    """, Files.readString(file));
        }
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void passwordWithSpacesAndSymbolsIsQuoted() throws Exception {
        try (OptionFile option = OptionFile.create(work, "app", Secret.of("a b#c'd;e=f"), "db.example.org", 3306)) {
            assertTrue(Files.readString(option.file()).contains("password=\"a b#c'd;e=f\"\n"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with\"quote", "with\\backslash", "line\nbreak", "tab\there", "nul\u0000"})
    void unsafePasswordsAreRefusedWithoutQuotingThem(String password) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OptionFile.create(work, "root", Secret.of(password), "127.0.0.1", 3307));
        if (!password.isEmpty()) {
            assertFalse(e.getMessage().contains(password), e.getMessage());
        }
    }

    @Test
    void unsafeUserHostAndPortAreRefused() {
        Secret ok = Secret.of("password-123456");
        assertThrows(IllegalArgumentException.class, () -> OptionFile.create(work, "root\nhost=evil", ok, "127.0.0.1", 1));
        assertThrows(IllegalArgumentException.class, () -> OptionFile.create(work, "root", ok, "a b", 1));
        assertThrows(IllegalArgumentException.class, () -> OptionFile.create(work, "root", ok, "127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class, () -> OptionFile.create(work, "root", ok, "127.0.0.1", 65536));
    }

    @Test
    void leftoversOfACrashAreRemoved() throws Exception {
        Path old = Files.createDirectory(work.resolve(OptionFile.FOLDER_PREFIX + "123"));
        Files.writeString(old.resolve("client.cnf"), "password=\"x\"");
        Path unrelated = Files.createDirectory(work.resolve("keep"));
        OptionFile.removeLeftovers(work);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(unrelated));
    }
}
