package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz targets for {@link SqlQuoting}: whatever text comes in, a quoted literal or identifier can
 * never end early. In a normal test run they execute in regression mode (stored inputs only); the
 * nightly workflow fuzzes them on Linux.
 */
class SqlQuotingFuzzTest {

    @FuzzTest
    void literalNeverEndsEarly(@NotNull String value) {
        String quoted;
        try {
            quoted = SqlQuoting.literal(value);
        } catch (IllegalArgumentException refused) {
            assertTrue(value.indexOf('\\') >= 0 || value.length() > SqlQuoting.MAX_LITERAL_LENGTH
                    || value.chars().anyMatch(c -> Character.isISOControl((char) c)), "refused a harmless value");
            return;
        }
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'") && quoted.length() >= 2);
        String inner = quoted.substring(1, quoted.length() - 1);
        assertFalse(inner.replace("''", "").contains("'"), "a lone quote would end the literal");
        assertFalse(inner.contains("\\"));
        assertEquals(value, inner.replace("''", "'"));
    }

    @FuzzTest
    void identifierIsOnlyEverAllowlisted(@NotNull String name) {
        String quoted;
        try {
            quoted = SqlQuoting.identifier(name);
        } catch (IllegalArgumentException refused) {
            assertFalse(name.matches(SqlQuoting.NAME_PATTERN), "refused a valid name");
            return;
        }
        assertTrue(name.matches(SqlQuoting.NAME_PATTERN));
        assertEquals("`" + name + "`", quoted);
    }

    @FuzzTest
    void accountNeverBreaksOut(@NotNull String user, @NotNull String host) {
        String quoted;
        try {
            quoted = SqlQuoting.account(user, host);
        } catch (IllegalArgumentException refused) {
            return;
        }
        assertEquals("'" + user + "'@'" + host + "'", quoted);
        assertFalse(user.contains("'") || host.contains("'"));
    }
}
