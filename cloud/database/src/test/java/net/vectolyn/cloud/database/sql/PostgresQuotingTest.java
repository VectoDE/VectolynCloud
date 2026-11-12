// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PostgresQuotingTest {

    @Test
    void namesAreQuotedAndQuotesDoubled() {
        assertEquals("\"luckperms\"", PostgresQuoting.identifier("luckperms"));
        assertEquals("\"Public Schema\"", PostgresQuoting.identifier("Public Schema"));
        assertEquals("\"a\"\"; DROP TABLE x; --\"", PostgresQuoting.identifier("a\"; DROP TABLE x; --"));
        assertEquals("\"ümlaut\"", PostgresQuoting.identifier("ümlaut"));
    }

    @Test
    void emptyControlAndOverlongNamesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PostgresQuoting.identifier(""));
        assertThrows(IllegalArgumentException.class, () -> PostgresQuoting.identifier(null));
        assertThrows(IllegalArgumentException.class, () -> PostgresQuoting.identifier("a\u0000b"));
        assertThrows(IllegalArgumentException.class, () -> PostgresQuoting.identifier("a\nb"));
        assertEquals(65, PostgresQuoting.identifier("x".repeat(63)).length());
        assertThrows(IllegalArgumentException.class, () -> PostgresQuoting.identifier("x".repeat(64)));
        // 32 two-byte characters are 64 bytes.
        assertThrows(IllegalArgumentException.class, () -> PostgresQuoting.identifier("ä".repeat(32)));
    }
}
