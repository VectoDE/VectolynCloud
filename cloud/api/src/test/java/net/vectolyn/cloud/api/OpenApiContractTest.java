// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.api.http.Router;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Keeps {@code docs/cloud/api.openapi.yaml} and the routing table of the server in step. The build
 * passes the file as system property {@code vectolyn.openapi}; a route without its operation, an
 * operation without its route, a wrong scope in the description or a list endpoint without the
 * paging parameters fails the build.
 */
class OpenApiContractTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final Set<String> PAGED = Set.of("GET /services", "GET /groups", "GET /tasks", "GET /nodes",
            "GET /backups");

    private static JsonNode spec;

    @TempDir
    static Path home;

    @BeforeAll
    static void load() throws Exception {
        String file = System.getProperty("vectolyn.openapi");
        assertNotNull(file, "the build passes the OpenAPI file as -Dvectolyn.openapi");
        spec = YAMLMapper.builder().build().readTree(Files.readString(Path.of(file)));
    }

    @Test
    void everyRouteHasItsOperationAndNoOperationIsMissingItsRoute() throws Exception {
        Map<String, Router.Route<ApiRoutes.Handler>> routes = routes();
        Map<String, JsonNode> operations = operations();
        assertEquals(new TreeSet<>(routes.keySet()), new TreeSet<>(operations.keySet()),
                "docs/cloud/api.openapi.yaml and ApiRoutes.router() list different endpoints");
    }

    @Test
    void theDescriptionNamesTheScopeTheServerChecks() throws Exception {
        Map<String, Router.Route<ApiRoutes.Handler>> routes = routes();
        Map<String, JsonNode> operations = operations();
        for (Map.Entry<String, Router.Route<ApiRoutes.Handler>> entry : routes.entrySet()) {
            JsonNode operation = operations.get(entry.getKey());
            if (operation == null) {
                continue; // reported by the test above
            }
            String description = operation.path("description").asString("");
            if (entry.getValue().scope() == null) {
                assertFalse(description.contains("Scope:"), entry.getKey() + " needs no scope");
            } else {
                assertTrue(description.contains("Scope: " + entry.getValue().scope().id()),
                        entry.getKey() + " must say \"Scope: " + entry.getValue().scope().id() + "\"");
            }
            assertFalse(operation.path("operationId").asString("").isEmpty(), entry.getKey() + " needs an operationId");
        }
    }

    @Test
    void listEndpointsDocumentThePagingParametersAndHeaders() {
        Map<String, JsonNode> operations = operations();
        for (String key : PAGED) {
            JsonNode operation = operations.get(key);
            assertNotNull(operation, key);
            List<String> refs = new ArrayList<>();
            operation.path("parameters").forEach(p -> refs.add(p.path("$ref").asString("")));
            assertTrue(refs.contains("#/components/parameters/Limit") && refs.contains("#/components/parameters/After"),
                    key + " must list the Limit and After parameters");
            JsonNode headers = operation.path("responses").path("200").path("headers");
            assertTrue(headers.has("Link") && headers.has("X-Total-Count"), key + " must document Link and X-Total-Count");
        }
    }

    private static Map<String, Router.Route<ApiRoutes.Handler>> routes() throws Exception {
        Map<String, Router.Route<ApiRoutes.Handler>> result = new TreeMap<>();
        try (ConfigService config = ConfigService.open(CloudHome.of(home))) {
            for (Router.Route<ApiRoutes.Handler> route : new ApiRoutes(config, new FakeBackend(), () -> false)
                    .router().routes()) {
                assertTrue(route.path().startsWith(ApiRoutes.PREFIX), route.path());
                String key = route.method() + " " + route.path().substring(ApiRoutes.PREFIX.length());
                assertFalse(result.containsKey(key), "route twice: " + key);
                result.put(key, route);
            }
        }
        return result;
    }

    private static Map<String, JsonNode> operations() {
        Map<String, JsonNode> result = new TreeMap<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (METHODS.contains(operation.getKey())) {
                    result.put(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), operation.getValue());
                }
            }
        }
        return result;
    }
}
