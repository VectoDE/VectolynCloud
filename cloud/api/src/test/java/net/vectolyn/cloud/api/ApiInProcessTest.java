// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The in-process entry points the web panel uses. */
class ApiInProcessTest {

    @TempDir
    Path home;

    private static ApiToken local(Scope... scopes) {
        return new ApiToken(ApiServer.LOCAL_PREFIX + "alice", "panel:alice", Set.of(scopes), Instant.parse("2026-11-01T09:00:00Z"));
    }

    @Test
    void callsRunRoutesWithTheScopesOfTheUser() throws Exception {
        try (TestApi api = new TestApi(home)) {
            ApiResponse services = api.server.call(local(Scope.SERVICES_READ), "GET", "/services", new byte[0], null);
            assertEquals(200, services.status());
            ApiException missing = assertThrows(ApiException.class,
                    () -> api.server.call(local(Scope.SERVICES_READ), "GET", "/config", new byte[0], null));
            assertEquals(403, missing.status());
            assertEquals("missing-scope", missing.code());
            assertEquals(404, assertThrows(ApiException.class,
                    () -> api.server.call(local(Scope.ADMIN), "POST", "/setup/claim", new byte[0], null)).status(),
                    "the setup claim is not reachable in-process");
            assertEquals(404, assertThrows(ApiException.class,
                    () -> api.server.call(local(Scope.ADMIN), "GET", "/nothing", new byte[0], null)).status());
            ApiToken network = new ApiToken("a1b2c3d4", "x", Set.of(Scope.ADMIN), Instant.now());
            assertThrows(IllegalArgumentException.class, () -> api.server.call(network, "GET", "/services", new byte[0], null));
        }
    }

    @Test
    void theSetupTokenWorksOnceForThePanel() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String setup = api.tokens.issueSetupToken("owner", ApiTokens.SETUP_TOKEN_TTL).token().reveal();
            assertTrue(api.server.setupTokenPending());
            assertThrows(IOException.class, () -> api.server.useSetupToken(setup, admin -> {
                throw new IOException("disk full (test)");
            }));
            assertTrue(api.server.setupTokenPending(), "a failed use keeps the token");
            assertEquals(Optional.empty(), api.server.useSetupToken("vcs_wrong", admin -> admin));
            assertEquals(Optional.of("owner"), api.server.useSetupToken(setup, admin -> admin));
            assertFalse(api.server.setupTokenPending());
            assertEquals(Optional.empty(), api.server.useSetupToken(setup, admin -> admin), "used up");
            assertEquals(401, api.send("POST", "/api/v1/setup/claim", setup, null).statusCode(), "and gone for the API too");
        }
    }
}
