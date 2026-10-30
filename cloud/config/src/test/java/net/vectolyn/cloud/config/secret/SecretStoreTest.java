package net.vectolyn.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTest {

    @TempDir
    Path root;

    @Test
    void firstStartGeneratesEverySecret() throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        Set<String> generated = store.ensureGenerated(new SecretGenerator());
        Set<String> all = Arrays.stream(GeneratedSecret.values()).map(GeneratedSecret::key).collect(Collectors.toSet());
        assertEquals(all, generated);
        EnvFile file = store.load();
        all.forEach(key -> assertTrue(file.hasValue(key), key));
        assertTrue(Files.readString(store.file()).contains("# Floodgate AES key"), "new keys get a comment");
        assertEquals(Set.of(), store.ensureGenerated(new SecretGenerator()), "second start adds nothing");
        assertEquals(file, store.load(), "second start changes nothing");
    }

    @Test
    void existingValuesAreNeverOverwrittenAndCommentsStay() throws Exception {
        Path env = root.resolve("secrets/vectolyn.env");
        Files.createDirectories(env.getParent());
        String original = """
                # My own note, keep me
                VECTOLYN_FORWARDING_SECRET=mine-and-only-mine

                # Fill in later
                VECTOLYN_DB_PASSWORD=
                VECTOLYN_DB_HOST=127.0.0.1
                """;
        Files.writeString(env, original);
        SecretStore store = new SecretStore(env);

        Set<String> generated = store.ensureGenerated(new SecretGenerator());

        assertFalse(generated.contains("VECTOLYN_FORWARDING_SECRET"));
        assertTrue(generated.contains("VECTOLYN_DB_PASSWORD"), "empty entries are filled");
        List<String> lines = Files.readString(env).lines().toList();
        assertEquals("# My own note, keep me", lines.get(0));
        assertEquals("VECTOLYN_FORWARDING_SECRET=mine-and-only-mine", lines.get(1));
        assertEquals("# Fill in later", lines.get(3));
        assertTrue(lines.get(4).matches("VECTOLYN_DB_PASSWORD=[A-Za-z0-9]{32}"), "filled in place");
        assertEquals("VECTOLYN_DB_HOST=127.0.0.1", lines.get(5));

        assertFalse(store.putIfAbsent("VECTOLYN_FORWARDING_SECRET", Secret.of("attempt")));
        assertTrue(store.putIfAbsent("VECTOLYN_API_KEYSTORE_PASSWORD", Secret.of("keystore-pw")));
        assertEquals("mine-and-only-mine", store.load().get("VECTOLYN_FORWARDING_SECRET").orElseThrow().reveal());
    }

    @Test
    void malformedFileIsNotTouched() throws Exception {
        Path env = root.resolve("vectolyn.env");
        Files.writeString(env, "VECTOLYN_FORWARDING_SECRET=keep\nbroken line\n");
        SecretStore store = new SecretStore(env);
        EnvFormatException e = assertThrows(EnvFormatException.class, () -> store.ensureGenerated(new SecretGenerator()));
        assertEquals(2, e.line());
        assertEquals("VECTOLYN_FORWARDING_SECRET=keep\nbroken line\n", Files.readString(env));
    }

    @Test
    void failedWriteKeepsTheOldFile() throws Exception {
        Path env = root.resolve("vectolyn.env");
        Files.writeString(env, "# note\nVECTOLYN_DB_PASSWORD=old\n");
        SecretStore store = new SecretStore(env, new AtomicFileWriter((source, target, options) -> {
            throw new IOException("disk full");
        }));
        assertThrows(IOException.class, () -> store.ensureGenerated(new SecretGenerator()));
        assertEquals("# note\nVECTOLYN_DB_PASSWORD=old\n", Files.readString(env));
        try (var files = Files.list(root)) {
            assertEquals(List.of(env), files.toList(), "no temporary file with secrets is left behind");
        }
    }

    @Test
    void redactingFilterCoversEveryStoredValue() throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        store.ensureGenerated(new SecretGenerator());
        String password = store.load().get("VECTOLYN_DB_PASSWORD").orElseThrow().reveal();
        RedactingFilter filter = store.redactingFilter();
        assertEquals("jdbc password=*** ok", filter.redact("jdbc password=" + password + " ok"));
        assertFalse(store.toString().contains(password));
        assertFalse(store.load().toString().contains(password));
    }

    @Test
    void putReplacesAChosenValueInPlaceAndKeepsTheRest() throws Exception {
        Path env = root.resolve("secrets/vectolyn.env");
        Files.createDirectories(env.getParent());
        Files.writeString(env, """
                # note
                VECTOLYN_DB_PASSWORD=old-password-value
                VECTOLYN_FORWARDING_SECRET=keep-this-one-as-it-is
                """);
        SecretStore store = new SecretStore(env);

        assertTrue(store.put("VECTOLYN_DB_PASSWORD", Secret.of("new-password-value"), "ignored for an existing key"));

        assertEquals(List.of("# note", "VECTOLYN_DB_PASSWORD=new-password-value", "VECTOLYN_FORWARDING_SECRET=keep-this-one-as-it-is"),
                Files.readString(env).lines().toList());
        assertFalse(store.put("VECTOLYN_DB_PASSWORD", Secret.of("new-password-value"), null), "the same value writes nothing");
        assertTrue(store.put("VECTOLYN_EXTRA", Secret.of("appended-value"), "Extra key"));
        assertTrue(Files.readString(env).endsWith("# Extra key\nVECTOLYN_EXTRA=appended-value\n"), Files.readString(env));
    }

    @Test
    void putRefusesValuesThatDoNotFitOnOneLineWithoutWriting() throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        for (String bad : List.of(" padded", "padded ", "two\nlines", "nul\u0000zero")) {
            assertFalse(SecretStore.isStorable(Secret.of(bad)));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> store.put("VECTOLYN_DB_PASSWORD", Secret.of(bad), null));
            assertFalse(e.getMessage().contains(bad.strip()), "the message never repeats the value");
        }
        assertFalse(Files.exists(store.file()), "nothing was written");
        assertTrue(SecretStore.isStorable(Secret.of("a-normal-password")));
    }
}
