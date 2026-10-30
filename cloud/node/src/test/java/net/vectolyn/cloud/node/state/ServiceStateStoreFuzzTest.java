package net.vectolyn.cloud.node.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.file.Path;
import net.vectolyn.cloud.node.service.ServiceIds;

/**
 * Fuzz target for {@link ServiceStateStore#parse(byte[])}, the reader of the untrusted
 * {@code state/services.json}. In a normal test run it executes in regression mode (stored inputs
 * only); the nightly workflow fuzzes it on Linux.
 */
class ServiceStateStoreFuzzTest {

    @FuzzTest
    void parseFailsOnlyWithStateFileExceptionAndRoundTrips(byte @NotNull [] input) {
        ServiceStates states;
        try {
            states = ServiceStateStore.parse(input);
        } catch (StateFileException expected) {
            return;
        }
        byte[] written = ServiceStateStore.write(states);
        try {
            ServiceStates again = ServiceStateStore.parse(written);
            assertEquals(states, again, "write must produce a file that parses to the same state");
            assertArrayEquals(written, ServiceStateStore.write(again));
        } catch (StateFileException e) {
            throw new AssertionError("written state does not parse again: " + e.getMessage(), e);
        }
        for (ServiceRecord r : states.services()) {
            assertEquals(ServiceIds.of(r.group(), r.index()).value(), r.id());
            Path folder = Path.of(r.workDirectory());
            assertFalse(folder.isAbsolute(), "absolute work-directory accepted");
            assertTrue(folder.normalize().equals(folder) && !folder.startsWith(".."),
                    "work-directory leaves the home");
            assertTrue(r.pid() >= 0 && r.port() >= 1 && r.port() <= 65535);
        }
    }
}
