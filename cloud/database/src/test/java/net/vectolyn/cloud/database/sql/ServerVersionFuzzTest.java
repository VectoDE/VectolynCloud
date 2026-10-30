package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Optional;

/**
 * Fuzz target for {@link ServerVersion#parse}: the version string comes from the server and must
 * never make the parser throw.
 */
class ServerVersionFuzzTest {

    @FuzzTest
    void parseNeverThrows(String text) {
        Optional<ServerVersion> version = ServerVersion.parse(text);
        version.ifPresent(v -> {
            assertTrue(v.major() >= 0 && v.minor() >= 0 && v.patch() >= 0);
            assertTrue(v.major() <= 9999 && v.minor() <= 9999 && v.patch() <= 9999);
        });
    }
}
