// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.backend.PrivacyAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Access and deletion of the tracking data for the panel: admin only, 404, 501 and bad ids (400). */
@Timeout(60)
class PrivacyApiTest {

    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final String URL = "/api/v1/admin/privacy/players/" + PLAYER;

    @TempDir
    Path home;

    private static PrivacyAccess access(Map<UUID, Map<String, Object>> data) {
        return new PrivacyAccess() {
            @Override
            public Optional<Map<String, Object>> find(UUID player) {
                return Optional.ofNullable(data.get(player));
            }

            @Override
            public boolean delete(UUID player) {
                return data.remove(player) != null;
            }
        };
    }

    @Test
    void onlyAdminReadsAndDeletes() throws Exception {
        Map<UUID, Map<String, Object>> data = new HashMap<>();
        data.put(UUID.fromString(PLAYER), Map.of("uuid", PLAYER, "name", "Steve", "logins", 3));
        try (TestApi api = new TestApi(home)) {
            api.backend.privacy = access(data);
            String admin = api.token("privacy-admin", Scope.ADMIN);
            String other = api.token("privacy-other", Scope.SERVICES_READ, Scope.CONFIG_READ, Scope.NODE_READ);
            assertEquals(403, api.get(URL, other).statusCode());
            assertEquals(403, api.send("DELETE", URL, other, null).statusCode());

            HttpResponse<String> found = api.get(URL, admin);
            assertEquals(200, found.statusCode(), found.body());
            assertTrue(found.body().contains("\"name\":\"Steve\""), found.body());

            assertEquals(204, api.send("DELETE", URL, admin, null).statusCode());
            assertEquals(404, api.get(URL, admin).statusCode());
            assertEquals(404, api.send("DELETE", URL, admin, null).statusCode());
        }
    }

    @Test
    void withoutTrackingTheEndpointsAnswer501AndBadIdsAre400() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String admin = api.token("privacy-admin", Scope.ADMIN);
            assertEquals(501, api.get(URL, admin).statusCode());
            assertEquals(501, api.send("DELETE", URL, admin, null).statusCode());
            // a malformed id is refused before any handler runs
            assertEquals(400, api.get("/api/v1/admin/privacy/players/not-a-uuid", admin).statusCode());
        }
    }
}
