package net.vectolyn.cloud.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz target for {@link RuleParser}: any text either fails with {@link RuleSyntaxException}
 * (position inside the text, message without the text) or parses into a condition within the
 * metric's range whose canonical form parses back to an equal condition. Regression mode in every
 * test run; the nightly workflow fuzzes it on Linux.
 */
class RuleParserFuzzTest {

    @FuzzTest
    void parseFailsOnlyWithSyntaxErrorsAndRoundTrips(byte @NotNull [] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        RuleCondition condition;
        try {
            condition = RuleParser.parse(text);
        } catch (RuleSyntaxException e) {
            assertTrue(e.position() >= 0 && e.position() <= Math.max(text.length(), RuleParser.MAX_LENGTH));
            // The message is built from fixed texts and a number only (RuleParserTest checks that
            // it never quotes the input); it never carries a control character from the input.
            assertTrue(e.getMessage().chars().allMatch(c -> c >= 0x20 && c < 0x7F), "printable ASCII message");
            return;
        }
        assertTrue(text.length() <= RuleParser.MAX_LENGTH);
        double threshold = condition.threshold().value();
        assertTrue(threshold >= condition.metric().min() && threshold <= condition.metric().max());
        assertTrue(condition.comparison().watchesLow()
                ? condition.clearAt().value() >= threshold : condition.clearAt().value() <= threshold);
        assertFalse(condition.holdFor().isNegative());
        assertTrue(condition.holdFor().compareTo(RuleParser.MAX_DURATION) <= 0);
        assertTrue(condition.clearAfter().compareTo(RuleParser.MAX_DURATION) <= 0);
        assertEquals(condition.metric().isWindowCount(), !condition.window().isZero());
        assertEquals(condition, RuleParser.parse(condition.canonical()), "canonical form must parse back");
    }
}
