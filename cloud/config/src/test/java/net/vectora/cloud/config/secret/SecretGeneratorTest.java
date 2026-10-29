package net.vectora.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SecretGeneratorTest {

    private final SecretGenerator generator = new SecretGenerator();

    @Test
    void generatesTheDocumentedFormats() {
        Map<GeneratedSecret, Secret> all = generator.generateAll();
        assertEquals(EnumSet.allOf(GeneratedSecret.class), all.keySet());
        assertTrue(all.get(GeneratedSecret.MANAGEMENT_SECRET).reveal().matches("[A-Za-z0-9]{40}"));
        assertTrue(all.get(GeneratedSecret.FORWARDING_SECRET).reveal().matches("[A-Za-z0-9]{48}"));
        assertTrue(all.get(GeneratedSecret.LEGACY_FORWARDING_SECRET).reveal().matches("[A-Za-z0-9]{48}"));
        assertNotEquals(all.get(GeneratedSecret.FORWARDING_SECRET), all.get(GeneratedSecret.LEGACY_FORWARDING_SECRET),
                "the legacy proxy never gets the modern-forwarding secret");
        assertTrue(all.get(GeneratedSecret.DB_PASSWORD).reveal().matches("[A-Za-z0-9]{32}"));
        assertTrue(all.get(GeneratedSecret.DB_ROOT_PASSWORD).reveal().matches("[A-Za-z0-9]{32}"));
        byte[] floodgate = Base64.getDecoder().decode(all.get(GeneratedSecret.FLOODGATE_KEY).reveal());
        assertEquals(16, floodgate.length, "Floodgate needs a raw 16-byte AES key");
    }

    @Test
    void valuesDiffer() {
        assertNotEquals(generator.generate(GeneratedSecret.DB_PASSWORD), generator.generate(GeneratedSecret.DB_PASSWORD));
        assertNotEquals(generator.generate(GeneratedSecret.DB_PASSWORD), generator.generate(GeneratedSecret.DB_ROOT_PASSWORD));
    }

    @Test
    void usesTheWholeAlphabet() {
        Set<Character> seen = generator.alphanumeric(5000).reveal().chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
        assertEquals(62, seen.size());
    }

    /** The generator covers every secret key of the example file; the rest are connection settings from cloud.yml. */
    @Test
    void coversEverySecretOfTheExampleFile() throws IOException, EnvFormatException {
        Path example = Path.of("../dist/secrets/vectora.env.example");
        Set<String> keys = EnvFileParser.parse(Files.readString(example, StandardCharsets.UTF_8)).keys();
        Set<String> generated = EnumSet.allOf(GeneratedSecret.class).stream().map(GeneratedSecret::key).collect(Collectors.toSet());
        Set<String> connection = Set.of("VECTORA_DB_HOST", "VECTORA_DB_PORT", "VECTORA_DB_NAME", "VECTORA_DB_USER");
        assertTrue(keys.containsAll(generated), "every generated key is documented in the example");
        Set<String> rest = keys.stream().filter(k -> !generated.contains(k)).collect(Collectors.toSet());
        assertEquals(connection, rest);
        assertTrue(generated.contains("VECTORA_DB_ROOT_PASSWORD"));
    }
}
