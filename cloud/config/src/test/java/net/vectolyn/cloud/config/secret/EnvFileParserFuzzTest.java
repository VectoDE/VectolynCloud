package net.vectolyn.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for {@link EnvFileParser}. In a normal test run it executes in regression mode
 * (stored inputs only); the nightly workflow fuzzes it on Linux.
 */
class EnvFileParserFuzzTest {

    @FuzzTest
    void parseFailsOnlyWithEnvFormatExceptionAndRoundTrips(byte @NotNull [] input) {
        EnvFile file;
        try {
            file = EnvFileParser.parse(input);
        } catch (EnvFormatException expected) {
            assertTrue(expected.line() >= 0);
            return;
        }
        String rendered = file.render();
        try {
            EnvFile again = EnvFileParser.parse(rendered);
            assertEquals(file, again, "render must produce a file that parses to the same lines");
            assertEquals(rendered, again.render());
        } catch (EnvFormatException e) {
            throw new AssertionError("rendered output does not parse again: " + e.getMessage(), e);
        }
        for (EnvFile.Entry entry : file.entries()) {
            assertTrue(EnvFileParser.isValidKey(entry.key()));
            assertTrue(entry.value().reveal().indexOf('\n') < 0, "no multi-line values");
        }
    }
}
