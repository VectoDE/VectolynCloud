// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Token management over {@code /api/v1/admin/tokens}: create with a lifetime, list, expiry, revoke. */
@Timeout(120)
class AdminTokensTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"(vct_[A-Za-z0-9_-]{43})\"");
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f]{8})\"");

    @TempDir
    Path home;

    @Test
    void anAdminCreatesAnExpiringTokenThatStopsWorkingAndCanBeRevoked() throws Exception {
        try (TestApi api = new TestApi(home)) {
            HttpResponse<String> created = api.send("POST", "/api/v1/admin/tokens", api.admin,
                    "{\"name\":\"ci\",\"scopes\":[\"services:read\",\"node:read\"],\"expires-in\":\"24h\"}");
            assertEquals(201, created.statusCode(), created.body());
            assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
            Matcher token = TOKEN.matcher(created.body());
            assertTrue(token.find(), created.body());
            Matcher id = ID.matcher(created.body());
            assertTrue(id.find());
            assertTrue(created.body().contains("\"expires-at\":\"2026-11-02T10:00:00Z\""), created.body());
            assertFalse(Files.readString(api.tokens.file(), StandardCharsets.UTF_8).contains(token.group(1)),
                    "only the hash is stored");

            HttpResponse<String> self = api.get("/api/v1/tokens/self", token.group(1));
            assertEquals(200, self.statusCode());
            assertTrue(self.body().contains("\"scopes\":[\"node:read\",\"services:read\"]"), self.body());
            assertTrue(self.body().contains("\"expired\":false"));
            assertEquals(200, api.get("/api/v1/services", token.group(1)).statusCode());
            assertEquals(403, api.get("/api/v1/config", token.group(1)).statusCode());

            HttpResponse<String> list = api.get("/api/v1/admin/tokens", api.admin);
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("\"name\":\"ci\"") && list.body().contains("\"name\":\"admin\""));
            assertFalse(list.body().contains("vct_"), "a list never shows a token");
            assertFalse(list.body().contains("sha256"), "nor its hash");

            api.now.set(api.now.get().plus(Duration.ofHours(24)));
            assertEquals(401, api.get("/api/v1/services", token.group(1)).statusCode(), "expired");
            assertTrue(api.get("/api/v1/admin/tokens", api.admin).body().contains("\"expired\":true"));

            assertEquals(204, api.send("DELETE", "/api/v1/admin/tokens/" + id.group(1), api.admin, null).statusCode());
            assertEquals(404, api.send("DELETE", "/api/v1/admin/tokens/" + id.group(1), api.admin, null).statusCode());
            assertFalse(api.get("/api/v1/admin/tokens", api.admin).body().contains("\"name\":\"ci\""));
        }
    }

    @Test
    void revokingByNameStopsTheTokenAtOnce() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String script = api.token("script", Scope.SERVICES_WRITE);
            assertEquals(204, api.send("DELETE", "/api/v1/admin/tokens/script", api.admin, null).statusCode());
            assertEquals(401, api.get("/api/v1/tokens/self", script).statusCode());
        }
    }

    @Test
    void badBodiesAreRefused() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String[] bad = {
                "{\"scopes\":[\"admin\"]}",
                "{\"name\":\"Bad Name\",\"scopes\":[\"admin\"]}",
                "{\"name\":\"x\",\"scopes\":[]}",
                "{\"name\":\"x\",\"scopes\":[\"root\"]}",
                "{\"name\":\"x\",\"scopes\":[\"admin\"],\"expires-in\":\"30m\"}",
                "{\"name\":\"x\",\"scopes\":[\"admin\"],\"expires-in\":\"3651d\"}",
                "{\"name\":\"x\",\"scopes\":[\"admin\"],\"token\":\"vct_chosen\"}",
            };
            for (String body : bad) {
                HttpResponse<String> response = api.send("POST", "/api/v1/admin/tokens", api.admin, body);
                assertEquals(400, response.statusCode(), body + " -> " + response.body());
            }
            HttpResponse<String> taken = api.send("POST", "/api/v1/admin/tokens", api.admin,
                    "{\"name\":\"admin\",\"scopes\":[\"node:read\"]}");
            assertEquals(409, taken.statusCode());
            assertEquals(1, api.tokens.list().size(), "nothing was created");
        }
    }

    @Test
    void onlyAdminReachesTheAdminArea() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String almostEverything = api.token("almost", Scope.SERVICES_READ, Scope.SERVICES_WRITE, Scope.CONFIG_READ,
                    Scope.CONFIG_WRITE, Scope.BACKUPS_READ, Scope.BACKUPS_WRITE, Scope.NODE_READ);
            assertEquals(403, api.get("/api/v1/admin/tokens", almostEverything).statusCode());
            assertEquals(403, api.send("POST", "/api/v1/admin/tokens", almostEverything,
                    "{\"name\":\"escalate\",\"scopes\":[\"admin\"]}").statusCode());
            assertEquals(403, api.send("DELETE", "/api/v1/admin/tokens/admin", almostEverything, null).statusCode());
            assertEquals(2, api.tokens.list().size());
        }
    }
}
