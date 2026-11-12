// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rights check of every route against a real server: for each route and each scope, a token
 * holding only that scope is refused with {@code 403 missing-scope} exactly when the route needs
 * another scope, and {@code admin} gets through everywhere. The routes come from the server's own
 * table, so a new route is checked without touching this test.
 */
@Timeout(300)
class ScopeMatrixTest {

    @TempDir
    Path home;

    @Test
    void everyRouteRefusesExactlyTheTokensWithoutItsScope() throws Exception {
        try (TestApi api = new TestApi(home)) {
            Map<Scope, String> tokens = new EnumMap<>(Scope.class);
            for (Scope scope : Scope.values()) {
                tokens.put(scope, scope == Scope.ADMIN ? api.admin
                        : api.token(scope.id().replace(':', '-'), scope));
            }
            List<String> failures = new ArrayList<>();
            int checked = 0;
            for (Router.Route<ApiRoutes.Handler> route : api.server.router().routes()) {
                if (route.path().equals(ApiRoutes.EVENTS)) {
                    continue; // a WebSocket; EventStreamTest checks its scope on the handshake
                }
                String path = route.path().replace("{id}", "lobby-1").replace("{name}", "lobby")
                        .replace("{kind}", "lobby");
                for (Scope scope : Scope.values()) {
                    // Every request in its own minute, so the address limit never answers first.
                    api.nanos.addAndGet(60_000_000_000L);
                    String body = route.method().equals("GET") || route.method().equals("DELETE") ? null : "{}";
                    HttpResponse<String> response = api.send(route.method(), path, tokens.get(scope), body);
                    boolean refused = response.statusCode() == 403 && response.body().contains("\"missing-scope\"");
                    boolean allowed = route.scope() == null || scope == Scope.ADMIN || scope == route.scope();
                    String name = route.method() + " " + route.path() + " with " + scope.id();
                    checked++;
                    if (allowed && refused && !isSensitiveConfigWrite(route, scope)) {
                        failures.add(name + " was refused: " + response.body());
                    }
                    if (!allowed && !refused) {
                        failures.add(name + " was not refused: " + response.statusCode() + " " + response.body());
                    }
                    assertTrue(response.statusCode() != 401, name + " must authenticate");
                }
            }
            assertEquals(List.of(), failures);
            assertTrue(checked >= 8 * 30, "every route with every scope: " + checked);
        }
    }

    /**
     * {@code PUT /config} with {@code config:write} is refused with {@code missing-scope} when the
     * body would change {@code api} or {@code database}, which an empty body does; that needs
     * {@code admin} and is checked in {@code ApiServerTest}.
     */
    private static boolean isSensitiveConfigWrite(Router.Route<ApiRoutes.Handler> route, Scope scope) {
        return route.method().equals("PUT") && route.path().equals(ApiRoutes.PREFIX + "/config")
                && scope == Scope.CONFIG_WRITE;
    }
}
