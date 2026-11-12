// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import java.nio.charset.StandardCharsets;

/**
 * Quoting of PostgreSQL identifiers, for the statements the cloud shows the operator (the rights a
 * user lacks) and checks on the server. Names may come from the server ({@code current_schema()}),
 * so every name is quoted, never pasted.
 */
public final class PostgresQuoting {

    /** Longest identifier PostgreSQL keeps without cutting it (NAMEDATALEN - 1). */
    public static final int MAX_IDENTIFIER_BYTES = 63;

    private PostgresQuoting() {
    }

    /**
     * Quotes an identifier: {@code "name"}, with every {@code "} doubled.
     *
     * @param name the identifier
     * @return the quoted identifier
     * @throws IllegalArgumentException if the name is empty, longer than {@link #MAX_IDENTIFIER_BYTES}
     *                                  bytes or contains a control character
     */
    public static String identifier(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("identifier is empty");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException("identifier is longer than " + MAX_IDENTIFIER_BYTES + " bytes");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("identifier contains a control character");
            }
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
