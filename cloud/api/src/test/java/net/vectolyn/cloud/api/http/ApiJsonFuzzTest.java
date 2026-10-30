package net.vectolyn.cloud.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.Set;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.TaskConfig;

/**
 * Fuzz target for {@link ApiJson#read}: any request body either becomes a value or fails with a
 * 400 {@link ApiException} whose message has no control characters; no other exception escapes
 * to the handler. A value that was read can always be written again. Regression mode in every
 * test run; the nightly workflow fuzzes it on Linux.
 */
class ApiJsonFuzzTest {

    private static final Set<String> CODES = Set.of("body-required", "invalid-json", "unknown-field");
    private static final Class<?>[] TYPES = {GroupConfig.class, TaskConfig.class, CloudConfig.class,
        ApiRoutes.CommandBody.class, ApiRoutes.BackupBody.class};

    @FuzzTest
    void readFailsOnlyWithBadRequest(byte @NotNull [] input) {
        Class<?> type = TYPES[input.length == 0 ? 0 : Math.floorMod(input[input.length - 1], TYPES.length)];
        Object value;
        try {
            value = ApiJson.read(input, type);
        } catch (ApiException expected) {
            assertEquals(400, expected.status());
            assertTrue(CODES.contains(expected.code()), expected.code());
            String message = expected.getMessage();
            for (int i = 0; i < message.length(); i++) {
                assertTrue(message.charAt(i) >= 0x20 && message.charAt(i) != 0x7f, "no control characters");
            }
            return;
        }
        assertNotNull(value);
        assertTrue(ApiJson.write(value).length > 0);
    }
}
