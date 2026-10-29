package net.vectora.cloud.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import net.vectora.cloud.monitor.metric.Metric;
import org.junit.jupiter.api.Test;

class AlertEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");

    private static AlertEngine.Values tps(double value) {
        return (metric, window) -> metric == Metric.TPS ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    private static final AlertEngine.Values MISSING = (metric, window) -> OptionalDouble.empty();

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    private static List<AlertEvent> run(AlertEngine engine, long second, AlertEngine.Values values) {
        return engine.evaluateService(at(second), "lobby-1", "lobby", values);
    }

    @Test
    void firesOnlyAfterTheConditionHeldForTheWholeHoldTime() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18 for 60s", Severity.WARNING)));
        assertTrue(run(engine, 0, tps(10)).isEmpty(), "first bad sample starts the timer");
        assertTrue(run(engine, 30, tps(10)).isEmpty());
        assertTrue(run(engine, 59, tps(10)).isEmpty(), "59 s is not 60 s");
        List<AlertEvent> events = run(engine, 60, tps(10));
        assertEquals(1, events.size());
        AlertEvent.Fired fired = assertInstanceOf(AlertEvent.Fired.class, events.get(0));
        assertEquals("low-tps", fired.alert().rule());
        assertEquals("lobby-1", fired.alert().subject());
        assertEquals(10.0, fired.alert().value());
        assertEquals(at(60), fired.alert().firedAt());
        assertTrue(run(engine, 70, tps(10)).isEmpty(), "fires once, not on every sample");
        assertEquals(1, engine.active().size());
    }

    @Test
    void aShortDipDoesNotFire() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18 for 60s", Severity.WARNING)));
        run(engine, 0, tps(10));
        run(engine, 50, tps(10));
        run(engine, 55, tps(20));
        assertTrue(run(engine, 60, tps(10)).isEmpty(), "the timer started over at 60");
        assertTrue(run(engine, 110, tps(10)).isEmpty());
        assertEquals(1, run(engine, 120, tps(10)).size());
    }

    @Test
    void zeroHoldFiresOnTheFirstSample() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18", Severity.WARNING)));
        assertEquals(1, run(engine, 0, tps(17.99)).size());
    }

    @Test
    void thresholdItselfDoesNotFireAStrictRule() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18", Severity.WARNING),
                AlertRule.of("low-tps-incl", "tps <= 18", Severity.WARNING)));
        List<AlertEvent> events = run(engine, 0, tps(18));
        assertEquals(1, events.size());
        assertEquals("low-tps-incl", events.get(0).alert().rule());
    }

    @Test
    void hysteresisKeepsTheAlertBetweenThresholdAndClearValue() {
        AlertEngine engine = new AlertEngine(List.of(
                AlertRule.of("low-tps", "tps < 18 clear at 19 after 30s", Severity.WARNING)));
        assertEquals(1, run(engine, 0, tps(15)).size());
        assertTrue(run(engine, 10, tps(18.5)).isEmpty(), "18.5 is above the threshold but below the clear value");
        assertTrue(run(engine, 100, tps(18.9)).isEmpty());
        assertEquals(1, engine.active().size());
        assertTrue(run(engine, 110, tps(19)).isEmpty(), "healthy now, but clear-after starts");
        assertTrue(run(engine, 130, tps(19.5)).isEmpty());
        assertTrue(run(engine, 135, tps(18.5)).isEmpty(), "falls back: the clear timer resets");
        assertTrue(run(engine, 150, tps(19.5)).isEmpty());
        assertTrue(run(engine, 179, tps(19.5)).isEmpty());
        List<AlertEvent> events = run(engine, 180, tps(19.5));
        AlertEvent.Resolved resolved = assertInstanceOf(AlertEvent.Resolved.class, events.get(0));
        assertEquals(Alert.Resolution.CLEARED, resolved.alert().resolution().orElseThrow());
        assertEquals(at(180), resolved.alert().resolvedAt().orElseThrow());
        assertTrue(engine.active().isEmpty());
        assertEquals(1, engine.resolved().size());
    }

    @Test
    void withoutHysteresisFlappingValuesFireAndClearRepeatedly() {
        // Counter-check for the test above: the same values without "clear at" do clear.
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18", Severity.WARNING)));
        assertEquals(1, run(engine, 0, tps(15)).size());
        assertInstanceOf(AlertEvent.Resolved.class, run(engine, 10, tps(18.5)).get(0));
        assertInstanceOf(AlertEvent.Fired.class, run(engine, 20, tps(17.5)).get(0));
    }

    @Test
    void missingValuesNeitherFireNorClear() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18 for 60s", Severity.WARNING)));
        run(engine, 0, tps(10));
        run(engine, 30, MISSING);
        assertTrue(run(engine, 60, tps(10)).isEmpty(), "a gap restarts the pending timer");
        assertTrue(run(engine, 90, tps(10)).isEmpty());
        assertEquals(1, run(engine, 120, tps(10)).size());
        assertTrue(run(engine, 130, MISSING).isEmpty(), "missing data does not clear");
        assertTrue(run(engine, 2000, MISSING).isEmpty());
        assertEquals(1, engine.active().size());
    }

    @Test
    void groupFilterLimitsTheRule() {
        AlertRule rule = new AlertRule("survival-tps", RuleParser.parse("tps < 18"), Severity.WARNING, Set.of("survival"));
        AlertEngine engine = new AlertEngine(List.of(rule));
        assertTrue(engine.evaluateService(T0, "lobby-1", "lobby", tps(5)).isEmpty());
        assertEquals(1, engine.evaluateService(T0, "survival-1", "survival", tps(5)).size());
    }

    @Test
    void nodeRulesOnlyRunForTheNode() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-disk", "disk-free < 5GB", Severity.CRITICAL)));
        AlertEngine.Values disk = (metric, window) -> metric == Metric.DISK_FREE
                ? OptionalDouble.of(4.0 * 1024 * 1024 * 1024) : OptionalDouble.empty();
        assertTrue(engine.evaluateService(T0, "lobby-1", "lobby", disk).isEmpty());
        List<AlertEvent> events = engine.evaluateNode(T0, disk);
        assertEquals(Alert.NODE, events.get(0).alert().subject());
        assertEquals(Severity.CRITICAL, events.get(0).alert().severity());
        assertTrue(engine.retainServices(T0, s -> false).isEmpty(), "node alerts are never 'gone'");
    }

    @Test
    void goneServicesEndTheirAlerts() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18", Severity.WARNING)));
        run(engine, 0, tps(5));
        List<AlertEvent> events = engine.retainServices(at(5), s -> false);
        assertEquals(Alert.Resolution.SERVICE_GONE, events.get(0).alert().resolution().orElseThrow());
        assertTrue(engine.active().isEmpty());
        assertTrue(engine.retainServices(at(6), s -> false).isEmpty());
    }

    @Test
    void changedRulesEndTheirAlertsUnchangedOnesKeepThem() {
        AlertRule tpsRule = AlertRule.of("low-tps", "tps < 18", Severity.WARNING);
        AlertRule heapRule = AlertRule.of("heap", "heap > 90%", Severity.WARNING);
        AlertEngine engine = new AlertEngine(List.of(tpsRule, heapRule));
        AlertEngine.Values both = (metric, window) -> metric == Metric.TPS ? OptionalDouble.of(5) : OptionalDouble.of(95);
        assertEquals(2, run(engine, 0, both).size());
        List<AlertEvent> events = engine.replaceRules(at(1), List.of(tpsRule,
                AlertRule.of("heap", "heap > 95%", Severity.WARNING)));
        assertEquals(1, events.size());
        assertEquals("heap", events.get(0).alert().rule());
        assertEquals(Alert.Resolution.RULE_REMOVED, events.get(0).alert().resolution().orElseThrow());
        assertEquals(1, engine.active().size());
        assertEquals("low-tps", engine.active().get(0).rule());
    }

    @Test
    void acknowledgeMarksOnceAndUnknownIdsFail() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18", Severity.WARNING)));
        int id = run(engine, 0, tps(5)).get(0).alert().id();
        assertTrue(engine.acknowledge(id + 1, at(1)).isEmpty());
        AlertEvent ack = engine.acknowledge(id, at(1)).orElseThrow();
        assertTrue(ack.alert().acknowledged());
        assertTrue(engine.acknowledge(id, at(2)).isEmpty(), "already acknowledged");
        assertTrue(engine.active().get(0).acknowledged(), "stays active");
        assertTrue(engine.acknowledgeAll(at(3)).isEmpty());
    }

    @Test
    void alertIdsCountUpAcrossFirings() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("low-tps", "tps < 18", Severity.WARNING)));
        int first = run(engine, 0, tps(5)).get(0).alert().id();
        run(engine, 1, tps(20));
        int second = run(engine, 2, tps(5)).get(0).alert().id();
        assertEquals(first + 1, second);
    }

    @Test
    void duplicateRuleNamesAreRefused() {
        AlertRule rule = AlertRule.of("x", "tps < 18", Severity.WARNING);
        assertThrows(IllegalArgumentException.class, () -> new AlertEngine(List.of(rule, rule)));
        assertThrows(IllegalArgumentException.class, () -> AlertRule.of("Bad Name", "tps < 18", Severity.WARNING));
    }

    @Test
    void crashWindowCountFires() {
        AlertEngine engine = new AlertEngine(List.of(AlertRule.of("crash-loop", "crashes >= 3 within 10m", Severity.CRITICAL)));
        AlertEngine.Values two = (metric, window) -> OptionalDouble.of(2);
        AlertEngine.Values three = (metric, window) -> OptionalDouble.of(3);
        assertTrue(run(engine, 0, two).isEmpty());
        assertEquals(1, run(engine, 1, three).size(), "fires at once, no hold");
        assertInstanceOf(AlertEvent.Resolved.class, run(engine, 700, two).get(0), "clears when the window drops below");
    }
}
