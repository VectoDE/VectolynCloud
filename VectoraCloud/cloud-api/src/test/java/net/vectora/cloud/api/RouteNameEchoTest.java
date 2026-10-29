package net.vectora.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import net.vectora.cloud.api.http.ApiException;
import net.vectora.cloud.api.http.ApiJson;
import net.vectora.cloud.api.http.ApiRequest;
import net.vectora.cloud.api.http.ApiRoutes;
import net.vectora.cloud.api.http.Router;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigError;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The router lets only plain names into {@code {name}}, but the handlers of
 * {@code PUT /groups/{name}} and {@code PUT /tasks/{name}} must not rely on it: the name they echo
 * in the {@code file} field of a 400 goes through {@code ConfigNames.safeArgument}.
 */
class RouteNameEchoTest {

    private static final String HOSTILE = "evil\n\u001b[31m\u0000 " + "x".repeat(500);

    @TempDir
    Path home;

    @ParameterizedTest
    @ValueSource(strings = {"groups", "tasks"})
    void aHostileNameIsNotEchoedRaw(String kind) throws Exception {
        ApiException refused = refusedBody(kind, HOSTILE);
        assertEquals(400, refused.status());
        assertFalse(refused.errors().isEmpty());
        for (ConfigError error : refused.errors()) {
            String file = error.file();
            assertTrue(file.startsWith(kind + "/") && file.endsWith(".yml"), file);
            assertTrue(file.chars().allMatch(c -> c >= 0x20 && c < 0x7f), file);
            assertTrue(file.length() <= kind.length() + 1 + 64 + ".yml".length(), file);
        }
        String body = new String(ApiJson.error(refused), StandardCharsets.UTF_8);
        assertFalse(body.contains("xxxxxxxxxx".repeat(10)), body);
        assertFalse(body.contains("\\n") || body.contains("\\u001b") || body.contains("\\u0000"), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "tasks"})
    void aPlainNameIsEchoedAsItIs(String kind) throws Exception {
        // Counter-check: a valid name reaches the answer unchanged.
        ApiException refused = refusedBody(kind, "lobby");
        assertEquals(kind + "/lobby.yml", refused.errors().getFirst().file());
    }

    @SuppressWarnings("unchecked")
    private ApiException refusedBody(String kind, String name) throws Exception {
        try (ConfigService config = ConfigService.open(CloudHome.of(home))) {
            Router<ApiRoutes.Handler> router = new ApiRoutes(config, new FakeBackend(), () -> false).router();
            Router.Resolution<ApiRoutes.Handler> resolution = router.resolve("PUT", "/api/v1/" + kind + "/lobby");
            ApiRoutes.Handler handler = ((Router.Resolution.Found<ApiRoutes.Handler>) assertInstanceOf(
                    Router.Resolution.Found.class, resolution)).route().handler();
            byte[] body = "{\"schema-version\":1,\"unknown-field\":true}".getBytes(StandardCharsets.UTF_8);
            ApiRequest request = new ApiRequest("PUT", Map.of("name", name), Map.of(), body, null, null);
            return assertThrows(ApiException.class, () -> handler.handle(request));
        }
    }
}
