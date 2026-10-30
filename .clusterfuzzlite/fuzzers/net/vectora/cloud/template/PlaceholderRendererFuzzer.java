package net.vectora.cloud.template;

import java.util.Map;

/**
 * ClusterFuzzLite harness for {@link PlaceholderRenderer}. The same property as
 * PlaceholderRendererFuzzTest in cloud/template: only {@link TemplateException} may escape.
 */
public final class PlaceholderRendererFuzzer {

    private static final PlaceholderValues VALUES = PlaceholderValues.of(Map.of(
            "VECTORA_SERVER_PORT", "25566",
            "VECTORA_PROXY_SERVERS", "lobby = \"127.0.0.1:25566\"\r\nend = \"127.0.0.1:25570\"",
            "VECTORA_EMPTY", ""));

    private PlaceholderRendererFuzzer() {
    }

    public static void fuzzerTestOneInput(byte[] input) {
        try {
            PlaceholderRenderer.renderUtf8(input, "fuzz.yml", VALUES);
        } catch (TemplateException expected) {
            // a template that does not render is the normal answer to bad input
        }
    }
}
