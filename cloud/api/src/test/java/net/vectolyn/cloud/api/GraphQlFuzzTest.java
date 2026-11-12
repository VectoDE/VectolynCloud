// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import graphql.ExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiRoutes;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigException;
import org.reactivestreams.Publisher;

/**
 * Fuzz targets for the GraphQL endpoint: any document runs to a result (data, errors or both)
 * without an exception, whatever the parser, the validation, the limits or the fields make of it;
 * any request body is either a GraphQL request or a 400. The token may only read, so a fuzzed
 * mutation is refused by its route. Regression mode in every test run; the nightly workflow
 * fuzzes them on Linux.
 */
class GraphQlFuzzTest {

    private static final Set<String> CODES = Set.of("body-required", "invalid-json", "invalid-graphql-request");
    private static final ApiToken READER = new ApiToken("reader", "reader",
            EnumSet.of(Scope.SERVICES_READ, Scope.CONFIG_READ, Scope.NODE_READ, Scope.BACKUPS_READ),
            Instant.parse("2026-11-01T00:00:00Z"));
    private static final GraphQlApi API = api();

    private static GraphQlApi api() {
        try {
            Path home = Files.createTempDirectory("vectolyn-graphql-fuzz");
            ConfigService config = ConfigService.open(CloudHome.of(home));
            return new GraphQlApi(new ApiRoutes(config, new FakeBackend(), () -> false).router(),
                    e -> e instanceof ApiException api ? api : new ApiException(500, "internal-error", "Internal error."),
                    tokenId -> 0L, true);
        } catch (IOException | ConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    @FuzzTest
    void anyDocumentEndsInAResult(byte @NotNull [] input) {
        String document = new String(input, java.nio.charset.StandardCharsets.UTF_8);
        ExecutionResult result = API.execute(READER, document, null, Map.of(), null);
        assertNotNull(result);
        assertTrue(!(result.getData() instanceof Publisher<?>), "no subscription without a WebSocket");
        Map<String, Object> specification = result.toSpecification();
        assertTrue(specification.containsKey("data") || specification.containsKey("errors"));
        assertTrue(ApiJson.write(specification).length > 0);
    }

    @FuzzTest
    void anyBodyIsARequestOrABadRequest(byte @NotNull [] body) {
        try {
            GraphQlApi.Body request = GraphQlApi.Body.read(body);
            assertNotNull(request.query());
            assertTrue(request.query().length() <= GraphQlApi.MAX_CHARACTERS);
        } catch (ApiException expected) {
            assertEquals(400, expected.status());
            assertTrue(CODES.contains(expected.code()), expected.code());
        }
    }
}
