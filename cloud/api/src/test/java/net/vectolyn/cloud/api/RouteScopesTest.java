// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rights audit of the API (docs/cloud/rights-audit.md): every action that changes something needs a
 * write scope, checked by the server for each request; reading needs a read scope. Everything under
 * {@code /admin/} (token management) needs {@code admin}. Without a route scope are only a token
 * describing itself and the event stream, which checks a scope per event type.
 */
class RouteScopesTest {

    private static final Set<Scope> WRITE = Set.of(Scope.SERVICES_WRITE, Scope.CONFIG_WRITE, Scope.BACKUPS_WRITE);

    @TempDir
    Path home;

    @Test
    void everyChangeNeedsAWriteScopeAndEveryReadAReadScope() throws Exception {
        List<String> unscoped = new ArrayList<>();
        try (ConfigService config = ConfigService.open(CloudHome.of(home))) {
            Router<ApiRoutes.Handler> router = new ApiRoutes(config, new FakeBackend(), () -> false).router();
            assertFalse(router.routes().isEmpty());
            for (Router.Route<ApiRoutes.Handler> route : router.routes()) {
                String name = route.method() + " " + route.path();
                boolean adminArea = route.path().startsWith(ApiRoutes.PREFIX + "/admin/");
                if (route.scope() == null) {
                    unscoped.add(name);
                } else if (adminArea) {
                    assertEquals(Scope.ADMIN, route.scope(), name + ": the admin area needs the scope admin");
                } else if (route.method().equals("GET")) {
                    assertFalse(WRITE.contains(route.scope()), name + " reads with a write scope");
                    assertFalse(route.scope() == Scope.ADMIN, name);
                } else {
                    assertTrue(WRITE.contains(route.scope()), name + " changes something without a write scope");
                }
            }
        }
        // The event stream checks a scope per event type on the handshake (EventStreamTest).
        assertEquals(List.of("GET /api/v1/tokens/self", "GET /api/v1/events"), unscoped,
                "only a token describing itself and the event stream need no route scope");
    }
}
