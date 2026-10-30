package net.vectolyn.cloud.console.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ClusterFuzzLite harness for {@link CommandLineParser}. A typed line either fails with
 * {@link CommandSyntaxException} or splits into words that join back into a line that splits the same way.
 */
public final class CommandLineParserFuzzer {

    private CommandLineParserFuzzer() {
    }

    public static void fuzzerTestOneInput(byte[] input) {
        String line = new String(input, StandardCharsets.UTF_8);
        CommandLineParser.partial(line);
        List<String> words;
        try {
            words = CommandLineParser.tokenize(line);
        } catch (CommandSyntaxException expected) {
            return;
        }
        try {
            if (!words.equals(CommandLineParser.tokenize(CommandLineParser.join(words)))) {
                throw new IllegalStateException("join gave a line that splits differently");
            }
        } catch (CommandSyntaxException e) {
            if (e.reason() != CommandSyntaxException.Reason.TOO_LONG) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }
}
