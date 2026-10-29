package net.vectora.cloud.node.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
