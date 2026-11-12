// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import java.util.Set;
import net.vectolyn.cloud.api.auth.Scope;
import org.junit.jupiter.api.Test;

class RouterTest {

    private final Router<String> router = new Router<String>()
            .add("GET", "/api/v1/services", Scope.SERVICES_READ, "list")
            .add("GET", "/api/v1/services/{id}", Scope.SERVICES_READ, "info")
            .add("POST", "/api/v1/services/{id}/stop", Scope.SERVICES_WRITE, "stop")
            .add("DELETE", "/api/v1/groups/{name}", Scope.CONFIG_WRITE, "delete")
            .add("PUT", "/api/v1/servers/{kind}", Scope.CONFIG_WRITE, "wanted")
            .add("GET", "/api/v1/templates/{template}/files", Scope.TEMPLATES_READ, "files")
            .add("GET", "/api/v1/templates/{template}/files/{path}", Scope.TEMPLATES_READ, "file");

    @Test
    void theTailTakesTheRestOfThePath() {
        var file = assertInstanceOf(Router.Resolution.Found.class,
                router.resolve("GET", "/api/v1/templates/Survival-Server/files/default/plugins/LuckPerms/config.yml"));
        assertEquals("file", file.route().handler());
        assertEquals(Map.of("template", "Survival-Server", "path", "default/plugins/LuckPerms/config.yml"), file.params());
        var jar = assertInstanceOf(Router.Resolution.Found.class,
                router.resolve("GET", "/api/v1/templates/Proxy/files/default/plugins/LuckPerms-Velocity-5.4.137.jar"));
        assertEquals("default/plugins/LuckPerms-Velocity-5.4.137.jar", jar.params().get("path"));
        var list = assertInstanceOf(Router.Resolution.Found.class, router.resolve("GET", "/api/v1/templates/Proxy/files"));
        assertEquals("files", list.route().handler());
        for (String target : new String[] {"/api/v1/templates/Proxy/files/default/../../secrets/vectolyn.env",
                "/api/v1/templates/Proxy/files/./x", "/api/v1/templates/Proxy/files/a//b", "/api/v1/templates/Proxy/files/a/",
                "/api/v1/templates/Proxy/files/%2e%2e/x", "/api/v1/templates/Proxy/files/a%2Fb", "/api/v1/templates/Proxy/files/a\\b",
                "/api/v1/templates/../files/x", "/api/v1/templates/.hidden/files/x", "/api/v1/templates/Proxy/files/a..b",
                "/api/v1/templates/Proxy/files/.env"}) {
            assertInstanceOf(Router.Resolution.Invalid.class, router.resolve("GET", target), target);
        }
    }

    @Test
    void aTailOnlyEndsARoute() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Router<String>().add("GET", "/api/v1/{path}/x", null, "x"));
    }

    @Test
    void findsRoutesAndParameters() {
        var found = assertInstanceOf(Router.Resolution.Found.class, router.resolve("POST", "/api/v1/services/lobby-1/stop"));
        assertEquals("stop", found.route().handler());
        assertEquals(Map.of("id", "lobby-1"), found.params());
        var delete = assertInstanceOf(Router.Resolution.Found.class,
                router.resolve("DELETE", "/api/v1/groups/lobby?confirm=lobby"));
        assertEquals(Map.of("confirm", "lobby"), delete.query());
        var encoded = assertInstanceOf(Router.Resolution.Found.class,
                router.resolve("DELETE", "/api/v1/groups/lobby?confirm=lob%62y"));
        assertEquals("lobby", encoded.query().get("confirm"));
    }

    @Test
    void wrongMethodAndUnknownPath() {
        assertEquals(new Router.Resolution.MethodNotAllowed<String>(Set.of("GET")),
                router.resolve("DELETE", "/api/v1/services"));
        assertInstanceOf(Router.Resolution.NotFound.class, router.resolve("GET", "/api/v1/nothing"));
        assertInstanceOf(Router.Resolution.NotFound.class, router.resolve("GET", "/api/v1/services/lobby-1/stop/x"));
    }

    @Test
    void refusesEverythingOdd() {
        for (String target : new String[] {"", "api/v1/services", "/api/v1/services/", "//api/v1/services",
                "/api/v1/services/..", "/api/v1/services/.", "/api/v1/services/%2e%2e", "/api/v1/services/lobby%2F1",
                "/api/v1/services/Lobby-1", "/api/v1/services/lobby 1", "/api/v1/services/lobbyé1",
                "/api/v1/services?x=1&x=2", "/api/v1/services?X=1", "/api/v1/services?x=%zz", "/api/v1/services?x=a%00",
                "/api/v1/services?x=<script>", "/api/v1/services#frag", "/api/v1/servers/" + "a".repeat(17),
                "/" + "a".repeat(Router.MAX_TARGET)}) {
            assertInstanceOf(Router.Resolution.Invalid.class, router.resolve("GET", target), target);
        }
        assertInstanceOf(Router.Resolution.Invalid.class, router.resolve(null, "/api/v1/services"));
        assertInstanceOf(Router.Resolution.Invalid.class, router.resolve("GET", null));
    }
}
