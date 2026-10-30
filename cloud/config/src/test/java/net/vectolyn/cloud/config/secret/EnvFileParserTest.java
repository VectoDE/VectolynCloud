package net.vectolyn.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.secret.EnvFormatException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EnvFileParserTest {

    private static final String EXAMPLE = """
            # Copy to vectolyn.env and fill in.

            # Velocity modern-forwarding secret
            VECTOLYN_FORWARDING_SECRET=abc=def#not-a-comment
            VECTOLYN_DB_PASSWORD=
              # indented comment
            VECTOLYN_DB_PORT=3307
            """;

    @Test
    void readsCommentsBlanksAndEntries() throws EnvFormatException {
        EnvFile file = EnvFileParser.parse(EXAMPLE);
        assertEquals(7, file.lines().size());
        assertEquals(Set.of("VECTOLYN_FORWARDING_SECRET", "VECTOLYN_DB_PASSWORD", "VECTOLYN_DB_PORT"), file.keys());
        assertEquals("abc=def#not-a-comment", file.get("VECTOLYN_FORWARDING_SECRET").orElseThrow().reveal());
        assertTrue(file.get("VECTOLYN_DB_PASSWORD").orElseThrow().isEmpty());
        assertFalse(file.hasValue("VECTOLYN_DB_PASSWORD"));
        assertTrue(file.hasValue("VECTOLYN_DB_PORT"));
        assertTrue(file.lines().get(5) instanceof EnvFile.Comment);
    }

    @Test
    void renderKeepsEveryLine() throws EnvFormatException {
        assertEquals(EXAMPLE, EnvFileParser.parse(EXAMPLE).render());
    }

    @Test
    void acceptsCrLfBomAndMissingFinalNewline() throws EnvFormatException {
        EnvFile file = EnvFileParser.parse("﻿A=1\r\nB=2".getBytes(StandardCharsets.UTF_8));
        assertEquals("A=1\nB=2\n", file.render());
        assertEquals(EnvFile.empty(), EnvFileParser.parse(""));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "lower=1|INVALID_KEY|1",
        "A-B=1|INVALID_KEY|1",
        "export A=1|INVALID_KEY|1",
        "' A=1'|INVALID_KEY|1",
        "=1|INVALID_KEY|1",
        "JUSTTEXT|MISSING_SEPARATOR|1",
        "A=1\\nA=2|DUPLICATE_KEY|2",
        "A= padded|SURROUNDING_WHITESPACE|1",
        "'A=padded '|SURROUNDING_WHITESPACE|1",
        "A=line\\rbreak|CONTROL_CHARACTER|1",
        "A=nul\\u0000|CONTROL_CHARACTER|1",
        "# comment\\nB=x\\u2028y|CONTROL_CHARACTER|2",
    })
    void rejectsMalformedLines(String input, Reason reason, int line) {
        String text = input.replace("\\n", "\n").replace("\\r", "\r").replace("\\u0000", "\u0000").replace("\\u2028", " ");
        EnvFormatException e = assertThrows(EnvFormatException.class, () -> EnvFileParser.parse(text));
        assertEquals(reason, e.reason());
        assertEquals(line, e.line());
    }

    @Test
    void rejectsOversizedAndBinaryFiles() {
        assertEquals(Reason.TOO_LARGE, assertThrows(EnvFormatException.class,
                () -> EnvFileParser.parse(new byte[EnvFileParser.MAX_BYTES + 1])).reason());
        assertEquals(Reason.INVALID_ENCODING, assertThrows(EnvFormatException.class,
                () -> EnvFileParser.parse(new byte[] {'A', '=', (byte) 0xC3, (byte) 0x28})).reason());
        assertEquals(Reason.TOO_MANY_LINES, assertThrows(EnvFormatException.class,
                () -> EnvFileParser.parse("#\n".repeat(EnvFileParser.MAX_LINES + 1))).reason());
        assertEquals(Reason.INVALID_KEY, assertThrows(EnvFormatException.class,
                () -> EnvFileParser.parse("A".repeat(129) + "=1")).reason());
    }

    @Test
    void errorMessagesNameTheLineButNeverTheContent() {
        String secret = "Sup3rS3cretValue ";
        EnvFormatException e = assertThrows(EnvFormatException.class,
                () -> EnvFileParser.parse("# ok\nVECTOLYN_DB_PASSWORD=" + secret));
        assertEquals("vectolyn.env: line 2: the value must not start or end with whitespace", e.getMessage());
        assertEquals("vectolyn.env: Zeile 2: der Wert darf nicht mit Leerraum beginnen oder enden", e.describe(Language.DE));
        assertFalse(e.toString().contains("Sup3r"));
    }

    @Test
    void fillsEmptyEntriesInPlaceAndAppendsMissingOnes() throws EnvFormatException {
        EnvFile file = EnvFileParser.parse(EXAMPLE)
                .withValueIfAbsent("VECTOLYN_DB_PASSWORD", Secret.of("pw"), "ignored for existing keys")
                .withValueIfAbsent("VECTOLYN_FORWARDING_SECRET", Secret.of("other"), null)
                .withValueIfAbsent("NEW_KEY", Secret.of("new"), "New key");
        List<String> lines = file.render().lines().toList();
        assertEquals("VECTOLYN_DB_PASSWORD=pw", lines.get(4));
        assertEquals("VECTOLYN_FORWARDING_SECRET=abc=def#not-a-comment", lines.get(3), "existing value is never overwritten");
        assertEquals(List.of("", "# New key", "NEW_KEY=new"), lines.subList(lines.size() - 3, lines.size()));
    }

    @Test
    void refusesValuesThatWouldBreakTheFormat() throws EnvFormatException {
        EnvFile file = EnvFile.empty();
        assertThrows(IllegalArgumentException.class, () -> file.withValueIfAbsent("A", Secret.of("two\nlines"), null));
        assertThrows(IllegalArgumentException.class, () -> file.withValueIfAbsent("a", Secret.of("x"), null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> file.withValueIfAbsent("A", Secret.of(" leaked-secret"), null));
        assertFalse(e.getMessage().contains("leaked-secret"));
    }
}
