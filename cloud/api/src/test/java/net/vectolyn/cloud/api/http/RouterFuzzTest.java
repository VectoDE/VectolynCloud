// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.backend.ApiBackend;
import net.vectolyn.cloud.api.backend.NodeAccess;
import net.vectolyn.cloud.api.backend.ServiceAccess;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigException;

/**
 * Fuzz target for the real routing table of {@link ApiRoutes#router()}: any request target
 * resolves without an exception, and a found route only ever carries parameters and query values from the strict alphabets, so no path
 * separator, dot segment, percent sign or control character reaches a handler. Regression mode in
 * every test run; the nightly workflow fuzzes it on Linux.
 */
class RouterFuzzTest {

    private static final Pattern PARAM = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern QUERY_KEY = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern QUERY_VALUE = Pattern.compile("[A-Za-z0-9._,-]{0,512}");
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "get", ""};

    /** The real routing table of the server, built once over an empty home. */
    private static final Router<ApiRoutes.Handler> ROUTER = realRouter();

    private static Router<ApiRoutes.Handler> realRouter() {
        try {
            Path home = Files.createTempDirectory("vectolyn-router-fuzz");
            ConfigService config = ConfigService.open(CloudHome.of(home));
            // Resolving never calls a handler, so the backend is never asked for anything.
            ApiBackend backend = new ApiBackend() {
                @Override
                public ServiceAccess services() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public NodeAccess node() {
                    throw new UnsupportedOperationException();
                }
            };
            return new ApiRoutes(config, backend, () -> false).router();
        } catch (IOException | ConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    @FuzzTest
    void resolveNeverThrowsAndOnlyPassesCleanValues(byte @NotNull [] input) {
        String method = input.length == 0 ? "GET" : METHODS[Math.floorMod(input[0], METHODS.length)];
        String target = new String(input, Math.min(1, input.length), Math.max(0, input.length - 1),
                StandardCharsets.UTF_8);
        Router.Resolution<ApiRoutes.Handler> resolution = ROUTER.resolve(method, target);
        assertNotNull(resolution);
        if (resolution instanceof Router.Resolution.Found<ApiRoutes.Handler> found) {
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
