package net.vectora.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlQuotingTest {

    @Test
    void quotesValidDatabaseNames() {
        assertEquals("`luckperms`", SqlQuoting.identifier("luckperms"));
        assertEquals("`Vectora_Ranks_2`", SqlQuoting.identifier("Vectora_Ranks_2"));
        assertEquals("`" + "a".repeat(64) + "`", SqlQuoting.identifier("a".repeat(64)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a`b", "luck perms", "db;DROP DATABASE mysql", "x'y", "a-b", "a.b", "a\\b", "ä",
            "a\u0000b"})
    void refusesBadDatabaseNames(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SqlQuoting.identifier(name));
        assertFalse(!name.isEmpty() && e.getMessage().contains(name), "message must not echo the value");
    }

    @Test
    void refusesTooLongDatabaseName() {
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.identifier("a".repeat(65)));
    }

    @Test
    void literalDoublesQuotes() {
        assertEquals("'abc'", SqlQuoting.literal("abc"));
        assertEquals("''", SqlQuoting.literal(""));
        assertEquals("'it''s'", SqlQuoting.literal("it's"));
        assertEquals("''' OR ''1''=''1'", SqlQuoting.literal("' OR '1'='1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\\b", "\\'", "line\nbreak", "cr\r", "tab\t", "nul\u0000", "sub\u001a", "del\u007f"})
    void literalRefusesBackslashAndControlCharacters(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SqlQuoting.literal(value));
        assertFalse(e.getMessage().contains(value), "message must not echo the value");
    }

    @Test
    void literalRefusesHugeValues() {
        assertEquals(SqlQuoting.MAX_LITERAL_LENGTH + 2, SqlQuoting.literal("x".repeat(SqlQuoting.MAX_LITERAL_LENGTH)).length());
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.literal("x".repeat(SqlQuoting.MAX_LITERAL_LENGTH + 1)));
    }

    @Test
    void injectionAttemptStaysInsideTheLiteral() {
        String attack = "x' ; DROP USER 'root'@'localhost'; -- ";
        String quoted = SqlQuoting.literal(attack);
        // Remove the outer quotes; every remaining quote must be part of a doubled pair.
        String inner = quoted.substring(1, quoted.length() - 1);
        assertEquals(attack, inner.replace("''", "'"));
        assertFalse(inner.replace("''", "").contains("'"));
    }

    @Test
    void accountChecksUserAndHost() {
        assertEquals("'luckperms'@'127.0.0.1'", SqlQuoting.account("luckperms", "127.0.0.1"));
        assertEquals("'app'@'%'", SqlQuoting.account("app", "%"));
        assertEquals("'app'@'::1'", SqlQuoting.account("app", "::1"));
        assertEquals("'app'@'db.example.net'", SqlQuoting.account("app", "db.example.net"));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.account("", "%"));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.account("a'b", "%"));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.account("app", "10.0.0.%"));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.account("app", "a'b"));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.account("app", ""));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.account("app", "-host"));
    }

    @Test
    void existingAccountAcceptsAnonymousAndWildcards() {
        assertEquals("''@'localhost'", SqlQuoting.existingAccount("", "localhost"));
        assertEquals("'root'@'10.0.0.%'", SqlQuoting.existingAccount("root", "10.0.0.%"));
        assertThrows(IllegalArgumentException.class, () -> SqlQuoting.existingAccount("x\\", "%"));
    }

    @Test
    void nameAndHostChecks() {
        assertTrue(SqlQuoting.isName("luckperms"));
        assertFalse(SqlQuoting.isName(null));
        assertTrue(SqlQuoting.isHost("localhost"));
        assertFalse(SqlQuoting.isHost(null));
        assertFalse(SqlQuoting.isHost("host name"));
    }
}
