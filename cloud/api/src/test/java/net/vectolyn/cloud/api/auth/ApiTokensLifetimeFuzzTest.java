// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.time.Duration;

/**
 * Fuzz target for {@link ApiTokens#parseLifetime(String)}, which reads the lifetime an administrator
 * types when creating a token. It may only fail with an {@link IllegalArgumentException}; a
 * lifetime it returns is between the documented minimum and maximum.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class ApiTokensLifetimeFuzzTest {

    @FuzzTest
    void parseLifetimeFailsOnlyWithIllegalArgument(@NotNull String input) {
        try {
            Duration lifetime = ApiTokens.parseLifetime(input);
            assertTrue(lifetime.compareTo(ApiTokens.MIN_LIFETIME) >= 0);
            assertTrue(lifetime.compareTo(ApiTokens.MAX_LIFETIME) <= 0);
        } catch (IllegalArgumentException expected) {
            // a rejected value is the normal answer to bad input
        }
    }
}
