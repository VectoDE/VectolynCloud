package net.vectora.cloud.node.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.monitor.metric.NodeSample;
import net.vectora.cloud.monitor.metric.ServiceSample;
import net.vectora.cloud.node.scaling.ScalingPlanner.Memory;
import net.vectora.cloud.node.scaling.ScalingPlanner.Signals;
import net.vectora.cloud.node.scaling.ScalingSignals.Report;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** How bridge reports, proxy counts and the node sample become the planner's signals. */
class ScalingSignalsTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final Duration STALE = Duration.ofSeconds(30);
    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final ServiceId GAME = new ServiceId("minigame-1");
    private static final long GB = 1024L * 1024L * 1024L;

    private static Report report(ServiceId id, Instant at, int online, int max) {
        return new Report(id, new ServiceSample(at, online, max, 20.0, 5.0, GB, 2 * GB, 1.0, GB));
    }

    private static Signals merge(List<Report> reports, Map<ServiceId, Integer> fromProxies, Set<ServiceId> watched,
                                 Optional<NodeSample> node) {
        return ScalingSignals.merge(reports, fromProxies, watched::contains, node, NOW, STALE, 512L * 1024 * 1024);
    }

    private static Signals merge(Report... reports) {
        return merge(List.of(reports), Map.of(), Set.of(), Optional.empty());
    }

    @Test
    void aReportOlderThanStaleAfterGivesNoCount() {
        assertEquals(Map.of(LOBBY, 7), merge(report(LOBBY, NOW.minus(STALE), 7, 50)).players(),
                "exactly stale-after old is still fresh");
        assertEquals(Map.of(), merge(report(LOBBY, NOW.minus(STALE).minusSeconds(1), 7, 50)).players(),
                "one second older: no known count, so the service neither scales up nor counts as empty");

        // An old report of 0 players must not make a server that stopped reporting look empty.
        assertEquals(Map.of(), merge(report(LOBBY, NOW.minus(Duration.ofMinutes(10)), 0, 50)).players());
    }

    @Test
    void anUnknownCountIsLeftOutAndACountIsCappedAtTheReportedLimit() {
        assertEquals(Map.of(), merge(report(LOBBY, NOW, ServiceSample.UNKNOWN, 50)).players());
        assertEquals(Map.of(LOBBY, 50), merge(report(LOBBY, NOW, 500, 50)).players(), "min(online, max)");
        assertEquals(Map.of(LOBBY, 500), merge(report(LOBBY, NOW, 500, ServiceSample.UNKNOWN)).players(),
                "no limit reported");
    }

    @Test
    void whileAProxyWatchesTheBackendTheLowerOfBothCountsIsUsed() {
        List<Report> reports = List.of(report(LOBBY, NOW, 40, 50), report(GAME, NOW, 3, 50));
        // A manipulated lobby-1 claims 40 players; the proxy sees 2 on it.
        Signals watched = merge(reports, Map.of(LOBBY, 2, GAME, 9), Set.of(LOBBY, GAME), Optional.empty());
        assertEquals(Map.of(LOBBY, 2, GAME, 3), watched.players());

        // A watched backend the proxy has no player on counts 0, whatever it reports.
        assertEquals(Map.of(LOBBY, 0, GAME, 3),
                merge(reports, Map.of(GAME, 9), Set.of(LOBBY, GAME), Optional.empty()).players());

        // Counter-check: without a connected proxy the bridge's count is used.
        assertEquals(Map.of(LOBBY, 40, GAME, 3), merge(reports, Map.of(LOBBY, 2), Set.of(), Optional.empty()).players());
    }

    @Test
    void theNodeMemoryIsUsedOnlyWhenFreshAndMeasured() {
        NodeSample fresh = new NodeSample(NOW.minus(STALE), 0.5, 2 * GB, 8 * GB, 0, 0);
        assertEquals(Optional.of(new Memory(2 * GB, 8 * GB, 512L * 1024 * 1024)),
                merge(List.of(), Map.of(), Set.of(), Optional.of(fresh)).memory());

        NodeSample old = new NodeSample(NOW.minus(STALE).minusSeconds(1), 0.5, 2 * GB, 8 * GB, 0, 0);
        assertEquals(Optional.empty(), merge(List.of(), Map.of(), Set.of(), Optional.of(old)).memory());

        NodeSample unknownTotal = new NodeSample(NOW, 0.5, 2 * GB, NodeSample.UNKNOWN, 0, 0);
        assertEquals(Optional.empty(), merge(List.of(), Map.of(), Set.of(), Optional.of(unknownTotal)).memory());
        NodeSample unknownUsed = new NodeSample(NOW, 0.5, NodeSample.UNKNOWN, 8 * GB, 0, 0);
        assertEquals(Optional.empty(), merge(List.of(), Map.of(), Set.of(), Optional.of(unknownUsed)).memory());
        assertEquals(Optional.empty(), merge(List.of(), Map.of(), Set.of(), Optional.empty()).memory());
    }

    @Test
    void theWarningTellsTheThreeCasesOfAMissingCountApart() {
        ScalingSignals.Missing none = ScalingSignals.missing(Optional.empty(), Optional.empty(), NOW, STALE);
        assertEquals(ScalingSignals.Missing.Cause.NO_SAMPLE, none.cause());
        assertTrue(none.describe().contains("no sample"), none.describe());

        // A recent sample without bridge values: the bridge is silent.
        ServiceSample recent = new ServiceSample(NOW.minusSeconds(5), ServiceSample.UNKNOWN, ServiceSample.UNKNOWN,
                Double.NaN, Double.NaN, ServiceSample.UNKNOWN, ServiceSample.UNKNOWN, Double.NaN, ServiceSample.UNKNOWN);
        ScalingSignals.Missing silent = ScalingSignals.missing(Optional.of(recent), Optional.of(NOW.minusSeconds(75)),
                NOW, STALE);
        assertEquals(ScalingSignals.Missing.Cause.STALE_REPORT, silent.cause());
        assertEquals("its bridge has not reported for 75 s (stale-after 30 s of monitor.yml)", silent.describe());
        ScalingSignals.Missing never = ScalingSignals.missing(Optional.of(recent), Optional.empty(), NOW, STALE);
        assertTrue(never.describe().contains("not reported since the server started"), never.describe());

        // The newest sample is older than stale-after: the monitor has stopped, whatever the bridge does.
        ServiceSample old = new ServiceSample(NOW.minusSeconds(31), 3, 50, 20, 5, GB, 2 * GB, 1, GB);
        ScalingSignals.Missing stalled = ScalingSignals.missing(Optional.of(old), Optional.of(NOW.minusSeconds(1)),
                NOW, STALE);
        assertEquals(ScalingSignals.Missing.Cause.MONITOR_STALLED, stalled.cause());
        assertEquals(Optional.of(Duration.ofSeconds(31)), stalled.age());
        assertTrue(stalled.describe().contains("newest sample is 31 s old"), stalled.describe());
        // Counter-check: exactly stale-after old is not stalled yet.
        ServiceSample edge = new ServiceSample(NOW.minus(STALE), ServiceSample.UNKNOWN, 50, 20, 5, GB, 2 * GB, 1, GB);
        assertEquals(ScalingSignals.Missing.Cause.STALE_REPORT,
                ScalingSignals.missing(Optional.of(edge), Optional.empty(), NOW, STALE).cause());
    }

    /**
     * The process probe of the monitor (tasklist on a busy Windows machine) takes longer than
     * stale-after: the scaler still sees the player count of every round.
     */
    @Test
    void aProbeSlowerThanStaleAfterLeavesThePlayerCountKnown() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Instant> time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        java.time.Clock clock = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return time.get();
            }
        };
        java.util.concurrent.CountDownLatch inProbe = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        net.vectora.cloud.monitor.metric.MetricSource slow = new net.vectora.cloud.monitor.metric.MetricSource() {
            @Override
            public Map<Long, ProcessMetrics> processes(Set<Long> pids) {
                inProbe.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Map.of();
            }

            @Override
            public NodeSample node(Instant at) {
                return new NodeSample(at, 10, 2 * GB, 8 * GB, 0, 0);
            }
        };
        net.vectora.cloud.monitor.ServiceDirectory directory = () -> List.of(new net.vectora.cloud.monitor.MonitoredService(
                GAME, "minigame", false, net.vectora.cloud.monitor.ServicePhase.RUNNING, java.util.OptionalLong.of(7), 0));
        net.vectora.cloud.monitor.Monitor monitor = net.vectora.cloud.monitor.Monitor.builder(directory, slow,
                net.vectora.cloud.monitor.config.MonitorConfigLoader.parse(
                        "schema-version: 1\nrules: []\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .clock(clock).build();
        Thread probe = Thread.ofPlatform().daemon().start(monitor::probeNow);
        try {
            assertTrue(inProbe.await(10, java.util.concurrent.TimeUnit.SECONDS));
            ScalingSignals signals = ScalingSignals.of(monitor,
                    new net.vectora.cloud.node.channel.NetworkPlayers((proxy, name) -> Optional.empty()), id -> false,
                    clock);
            // 120 s of rounds every 10 s, twice the default stale-after, while the probe hangs.
            for (int round = 0; round < 12; round++) {
                monitor.statusReported(new net.vectora.protocol.message.ServiceStatus(GAME, 4, 50, 20, 5, GB, 2 * GB));
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), monitor::sampleNow);
                time.set(time.get().plusSeconds(9));
                assertEquals(Map.of(GAME, 4), signals.read().players(), "round " + round);
                time.set(time.get().plusSeconds(1));
            }
            // Counter-check: once the rounds stop, the scaler sees the stalled monitor.
            time.set(time.get().plus(Duration.ofSeconds(61)));
            assertEquals(Map.of(), signals.read().players());
            assertEquals(ScalingSignals.Missing.Cause.MONITOR_STALLED, signals.whyMissing(GAME).cause());
        } finally {
            release.countDown();
            probe.join(10_000);
            monitor.close();
        }
    }
}
