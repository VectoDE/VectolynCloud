package net.vectora.cloud.api.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import net.vectora.cloud.api.auth.Scope;

/**
 * Fuzz target for {@link Router}: any request target resolves without an exception, and a found
 * route only ever carries parameters and query values from the strict alphabets, so no path
 * separator, dot segment, percent sign or control character reaches a handler. Regression mode in
 * every test run; the nightly workflow fuzzes it on Linux.
 */
class RouterFuzzTest {

    private static final Pattern PARAM = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern QUERY_KEY = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern QUERY_VALUE = Pattern.compile("[A-Za-z0-9._,-]{0,512}");
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "get", ""};

    /** The shapes of the real table: fixed paths, every parameter kind, nested and trailing ones. */
    private static final Router<String> ROUTER = new Router<String>()
            .add("GET", ApiRoutes.PREFIX + "/services", Scope.SERVICES_READ, "list")
            .add("GET", ApiRoutes.PREFIX + "/services/{id}", Scope.SERVICES_READ, "info")
            .add("POST", ApiRoutes.PREFIX + "/services/{id}/command", Scope.SERVICES_WRITE, "command")
            .add("PUT", ApiRoutes.PREFIX + "/groups/{name}", Scope.CONFIG_WRITE, "put")
            .add("DELETE", ApiRoutes.PREFIX + "/groups/{name}", Scope.CONFIG_WRITE, "delete")
            .add("PUT", ApiRoutes.PREFIX + "/groups/{name}/wanted", Scope.CONFIG_WRITE, "wanted")
            .add("PUT", ApiRoutes.PREFIX + "/servers/{kind}", Scope.CONFIG_WRITE, "kind")
            .add("GET", ApiRoutes.PREFIX + "/tokens/self", null, "self")
            .add("GET", ApiRoutes.PREFIX + "/events", Scope.SERVICES_READ, "events");

    @FuzzTest
    void resolveNeverThrowsAndOnlyPassesCleanValues(byte @NotNull [] input) {
        String method = input.length == 0 ? "GET" : METHODS[Math.floorMod(input[0], METHODS.length)];
        String target = new String(input, Math.min(1, input.length), Math.max(0, input.length - 1),
                StandardCharsets.UTF_8);
        Router.Resolution<String> resolution = ROUTER.resolve(method, target);
        assertNotNull(resolution);
        if (resolution instanceof Router.Resolution.Found<String> found) {
            assertTrue(target.startsWith(ApiRoutes.PREFIX), target);
            for (String value : found.params().values()) {
                assertTrue(PARAM.matcher(value).matches(), value);
            }
            for (Map.Entry<String, String> entry : found.query().entrySet()) {
                assertTrue(QUERY_KEY.matcher(entry.getKey()).matches(), entry.getKey());
                assertTrue(QUERY_VALUE.matcher(entry.getValue()).matches(), entry.getValue());
            }
            assertTrue(found.route().method().equals(method), "the method must match exactly");
        }
    }
}
