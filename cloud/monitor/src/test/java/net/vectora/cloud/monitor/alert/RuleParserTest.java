package net.vectora.cloud.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import net.vectora.cloud.monitor.alert.RuleSyntaxException.Reason;
import net.vectora.cloud.monitor.metric.Metric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleParserTest {

    @Test
    void parsesTpsRuleWithHoldClearAndAfter() {
        RuleCondition c = RuleParser.parse("tps < 18 for 60s clear at 19 after 30s");
        assertEquals(Metric.TPS, c.metric());
        assertEquals(Comparison.LESS, c.comparison());
        assertEquals(18.0, c.threshold().value());
        assertEquals(Duration.ofSeconds(60), c.holdFor());
        assertEquals(19.0, c.clearAt().value());
        assertEquals(Duration.ofSeconds(30), c.clearAfter());
        assertEquals("tps < 18 for 1m clear at 19 after 30s", c.canonical());
    }

    @Test
    void unitsConvertToBaseValues() {
        assertEquals(5.0 * 1024 * 1024 * 1024, RuleParser.parse("disk-free < 5GB").threshold().value());
        assertEquals(5.0 * 1024 * 1024 * 1024, RuleParser.parse("disk-free < 5 GB").threshold().value());
        assertEquals(512.0 * 1024 * 1024, RuleParser.parse("rss > 512MB").threshold().value());
        assertEquals(90.0, RuleParser.parse("heap > 90% for 1m").threshold().value());
        assertEquals(50.0, RuleParser.parse("mspt > 50ms").threshold().value());
        assertEquals(50.0, RuleParser.parse("mspt > 50").threshold().value());
    }

    @Test
    void defaultsClearAtThresholdAndClearAfterHold() {
        RuleCondition c = RuleParser.parse("heap >= 90% for 2m");
        assertEquals(c.threshold(), c.clearAt());
        assertEquals(Duration.ofMinutes(2), c.clearAfter());
        assertEquals("heap >= 90% for 2m", c.canonical());
    }

    @Test
    void crashesNeedAWindowAndTakeNoHold() {
        RuleCondition c = RuleParser.parse("crashes >= 3 within 10m");
        assertEquals(Duration.ofMinutes(10), c.window());
        assertEquals(Duration.ZERO, c.holdFor());
        assertEquals(Duration.ZERO, c.clearAfter());
        assertEquals("crashes >= 3 within 10m", c.canonical());
        assertReason(Reason.WINDOW_REQUIRED, "crashes >= 3");
        assertReason(Reason.HOLD_NOT_ALLOWED, "crashes >= 3 for 10m");
        assertReason(Reason.WINDOW_NOT_ALLOWED, "tps < 18 within 10m");
        assertReason(Reason.BAD_DURATION, "crashes >= 3 within 0s");
    }

    @Test
    void firesAndClearsWithHysteresis() {
        RuleCondition low = RuleParser.parse("tps < 18 clear at 19");
        assertTrue(low.fires(17.9));
        assertFalse(low.fires(18.0), "18 is not below 18");
        assertFalse(low.clears(18.5), "between threshold and clear value the alert stays");
        assertTrue(low.clears(19.0));
        RuleCondition high = RuleParser.parse("heap > 90% clear at 85%");
        assertTrue(high.fires(90.1));
        assertFalse(high.fires(90.0));
        assertFalse(high.clears(88));
        assertTrue(high.clears(85));
        RuleCondition inclusive = RuleParser.parse("tps <= 18");
        assertTrue(inclusive.fires(18.0));
        assertTrue(inclusive.clears(18.001));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''|EMPTY",
        "'   '|EMPTY",
        "foo < 1|UNKNOWN_METRIC",
        "TPS < 18|UNKNOWN_METRIC",
        "18 < tps|UNKNOWN_METRIC",
        "tps 18|EXPECTED_OPERATOR",
        "tps|EXPECTED_OPERATOR",
        "tps = 18|BAD_CHARACTER",
        "tps <|EXPECTED_NUMBER",
        "tps < for|EXPECTED_NUMBER",
        "tps < 018|BAD_NUMBER",
        "tps < 18.0001|BAD_NUMBER",
        "tps < 1.2.3|BAD_NUMBER",
        "tps < 12345678901|BAD_NUMBER",
        "tps < 1000.001|OUT_OF_RANGE",
        "heap > 101%|OUT_OF_RANGE",
        "mspt > 60001|OUT_OF_RANGE",
        "disk-free < 1025TB|OUT_OF_RANGE",
        "heap > 90|WRONG_UNIT",
        "heap > 90GB|WRONG_UNIT",
        "disk-free < 5|WRONG_UNIT",
        "disk-free < 5%|WRONG_UNIT",
        "tps < 18%|WRONG_UNIT",
        "tps < 18ms|WRONG_UNIT",
        "disk-free < 5gb|WRONG_UNIT",
        "players > 10.5|NOT_WHOLE",
        "crashes >= 2.5 within 1m|NOT_WHOLE",
        "tps < 18 for 25h|BAD_DURATION",
        "tps < 18 for 1d|BAD_DURATION",
        "tps < 18 for 60|BAD_DURATION",
        "tps < 18 for 1.5m|BAD_DURATION",
        "tps < 18 for 0060s|BAD_DURATION",
        "tps < 18 for 9999999h|BAD_DURATION",
        "tps < 18 clear at 17|CLEAR_ON_WRONG_SIDE",
        "heap > 90% clear at 95%|CLEAR_ON_WRONG_SIDE",
        "tps < 18 clear 19|UNEXPECTED_WORD",
        "tps < 18 and more|UNEXPECTED_WORD",
        "tps < 18 after 30s for 60s|UNEXPECTED_WORD",
        "tps < 18 for 60s for 60s|UNEXPECTED_WORD",
        "tps < 18 ; drop|BAD_CHARACTER",
        "tps < 18 -|BAD_CHARACTER",
    })
    void rejectsInvalidRules(String text, Reason reason) {
        assertReason(reason, text);
    }

    @Test
    void boundariesAreInclusive() {
        assertEquals(1000.0, RuleParser.parse("tps <= 1000").threshold().value());
        assertEquals(0.0, RuleParser.parse("tps < 0").threshold().value());
        assertEquals(100.0, RuleParser.parse("cpu > 100%").threshold().value());
        assertEquals(Duration.ofHours(24), RuleParser.parse("tps < 18 for 24h").holdFor());
        assertEquals(Duration.ZERO, RuleParser.parse("tps < 18 for 0s").holdFor());
        assertEquals(1024.0 * 1024 * 1024 * 1024 * 1024, RuleParser.parse("rss > 1024TB").threshold().value());
        String longest = "tps < 18" + " ".repeat(RuleParser.MAX_LENGTH - 8);
        assertEquals(RuleParser.MAX_LENGTH, longest.length());
        RuleParser.parse(longest);
        assertReason(Reason.TOO_LONG, longest + " ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tps < 18 for 60s clear at 19 after 30s", "disk-free < 5GB clear at 6GB",
        "crashes >= 3 within 10m after 5m", "mspt > 50ms for 30s", "node-memory > 95% for 5m clear at 90%",
        "restarts >= 3", "players > 100 for 1h", "heap-used > 2048MB", "tps<18for60s"})
    void canonicalFormParsesToTheSameCondition(String text) {
        RuleCondition parsed = RuleParser.parse(text);
        assertEquals(parsed, RuleParser.parse(parsed.canonical()));
    }

    @Test
    void errorMessagesNeverQuoteTheInput() {
        String secret = "tps < 18 clear at s3cretValue";
        RuleSyntaxException e = assertThrows(RuleSyntaxException.class, () -> RuleParser.parse(secret));
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
        RuleSyntaxException unknown = assertThrows(RuleSyntaxException.class, () -> RuleParser.parse("hunter2 < 1"));
        assertFalse(unknown.getMessage().contains("hunter2"), unknown.getMessage());
        RuleSyntaxException control = assertThrows(RuleSyntaxException.class, () -> RuleParser.parse("tps < 18\nrm"));
        assertEquals(Reason.BAD_CHARACTER, control.reason());
        assertEquals(8, control.position());
        RuleSyntaxException unicode = assertThrows(RuleSyntaxException.class, () -> RuleParser.parse("tps < ١٨"));
        assertEquals(Reason.BAD_CHARACTER, unicode.reason());
    }

    private static void assertReason(Reason reason, String text) {
        RuleSyntaxException e = assertThrows(RuleSyntaxException.class, () -> RuleParser.parse(text), text);
        assertEquals(reason, e.reason(), text + ": " + e.getMessage());
    }
}
