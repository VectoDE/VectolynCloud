package net.vectolyn.cloud.console.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fuzz target for {@link CommandLineParser}: whatever is typed at the console either fails with
 * {@link CommandSyntaxException} or splits into words without control characters, within the
 * limits, which {@link CommandLineParser#join} writes back so that they split the same way again.
 * The lenient scan used for completion never fails. Regression mode in every test run; the nightly
 * workflow fuzzes it on Linux.
 */
class CommandLineParserFuzzTest {

    @FuzzTest
    void tokenizeFailsOnlyWithSyntaxErrorsAndRoundTrips(byte @NotNull [] input) {
        String line = new String(input, StandardCharsets.UTF_8);
        CommandLineParser.partial(line);
        List<String> words;
        try {
            words = CommandLineParser.tokenize(line);
        } catch (CommandSyntaxException expected) {
            assertTrue(expected.detail() >= 0);
            return;
        }
        assertTrue(line.length() <= CommandLineParser.MAX_LENGTH);
        assertTrue(words.size() <= CommandLineParser.MAX_WORDS);
        for (String word : words) {
            for (int i = 0; i < word.length(); i++) {
                assertTrue(!CommandLineParser.isForbidden(word.charAt(i)), "no control characters in a word");
            }
        }
        String joined = CommandLineParser.join(words);
        try {
            assertEquals(words, CommandLineParser.tokenize(joined), "join must give a line that splits the same way");
        } catch (CommandSyntaxException e) {
            // join can exceed MAX_LENGTH by adding quotes; that is the only acceptable failure.
            assertEquals(CommandSyntaxException.Reason.TOO_LONG, e.reason(), e.getMessage());
        }
    }
}
