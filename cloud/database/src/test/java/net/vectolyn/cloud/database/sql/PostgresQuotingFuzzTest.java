// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz target for {@link PostgresQuoting}: a quoted identifier never ends early and always reads
 * back as the name. Names come from the server ({@code current_schema()}), so any text may arrive.
 * In a normal test run it executes in regression mode; the nightly workflow fuzzes it on Linux.
 */
class PostgresQuotingFuzzTest {

    @FuzzTest
    void identifierNeverEndsEarly(@NotNull String name) {
        String quoted;
        try {
            quoted = PostgresQuoting.identifier(name);
        } catch (IllegalArgumentException refused) {
            assertTrue(name.isEmpty()
                    || name.getBytes(StandardCharsets.UTF_8).length > PostgresQuoting.MAX_IDENTIFIER_BYTES
                    || name.chars().anyMatch(c -> Character.isISOControl((char) c)), "refused a harmless name");
            return;
        }
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\"") && quoted.length() >= 3);
        String inner = quoted.substring(1, quoted.length() - 1);
        assertFalse(inner.replace("\"\"", "").contains("\""), "a lone quote would end the identifier");
        assertFalse(inner.chars().anyMatch(c -> Character.isISOControl((char) c)));
        assertEquals(name, inner.replace("\"\"", "\""));
    }
}
