package net.vectora.cloud.template;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Fuzz target for {@link PlaceholderRenderer}. In a normal test run it executes in regression mode
 * (stored inputs only); the nightly workflow fuzzes it on Linux.
 */
class PlaceholderRendererFuzzTest {

    private static final PlaceholderValues VALUES = PlaceholderValues.of(Map.of(
            "VECTORA_SERVER_PORT", "25566",
            "VECTORA_PROXY_SERVERS", "lobby = \"127.0.0.1:25566\"\r\nend = \"127.0.0.1:25570\"",
            "VECTORA_EMPTY", ""));

    @FuzzTest
    void renderFailsOnlyWithTemplateException(byte @NotNull [] input) {
        try {
            byte[] rendered = PlaceholderRenderer.renderUtf8(input, "fuzz.yml", VALUES);
            String text = new String(input, StandardCharsets.UTF_8);
            if (!text.contains("{{")) {
                assertArrayEquals(input, rendered, "text without placeholders must stay byte for byte the same");
            }
            String out = new String(rendered, StandardCharsets.UTF_8);
            assertTrue(out.length() <= text.length() * 64L + 64, "output grew more than the values allow");
        } catch (TemplateException expected) {
            assertTrue(expected.getMessage().startsWith("fuzz.yml"), expected.getMessage());
            assertTrue(expected.line() >= TemplateException.NO_LINE);
        }
    }
}
