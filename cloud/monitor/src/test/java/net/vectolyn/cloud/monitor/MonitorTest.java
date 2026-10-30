package net.vectolyn.cloud.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.monitor.alert.Alert;
import net.vectolyn.cloud.monitor.alert.AlertEvent;
import net.vectolyn.cloud.monitor.config.MonitorConfigLoader;
import net.vectolyn.cloud.monitor.history.HistoryStore;
import net.vectolyn.cloud.monitor.metric.MetricSource;
import net.vectolyn.cloud.monitor.metric.ServiceSample;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.ServiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");
    private static final ServiceId LOBBY = new ServiceId("lobby-1");

    private final Fakes.MutableClock clock = new Fakes.MutableClock(T0);
    private final Fakes.Directory directory = new Fakes.Directory();
    private final Fakes.Source source = new Fakes.Source();
    private final List<AlertEvent> events = new CopyOnWriteArrayList<>();

    private Monitor monitor(String yaml) throws ConfigException {
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.parse(
                ("schema-version: 1\n" + yaml).getBytes(StandardCharsets.UTF_8));
        return Monitor.builder(directory, source, settings).clock(clock).listener(events::add).build();
    }

    private static ServiceStatus status(ServiceId id, double tps, long heapUsed, long heapMax) {
        return new ServiceStatus(id, 3, 100, tps, 50, heapUsed, heapMax);
    }

    /** Reports a status and samples, then moves the clock on. */
    private List<AlertEvent> step(Monitor monitor, ServiceStatus status, long seconds) {
        if (status != null) {
            monitor.statusReported(status);
        }
        monitor.probeNow();
        List<AlertEvent> round = monitor.sampleNow();
        clock.advanceSeconds(seconds);
        return round;
    }

    @Test
    void lowTpsFiresAfterTheHoldTimeAndIsLogged() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        source.processes.put(100L, new MetricSource.ProcessMetrics(12.5, 900L << 20));
        try (Fakes.LogCapture log = new Fakes.LogCapture("net.vectolyn.cloud.monitor");
             Monitor monitor = monitor("rules:\n  - name: low-tps\n    when: tps < 18 for 60s\n")) {
            for (int i = 0; i < 6; i++) {
                assertTrue(step(monitor, status(LOBBY, 12, 1, 2), 10).isEmpty(), "sample " + i);
            }
            List<AlertEvent> round = step(monitor, status(LOBBY, 12, 1, 2), 10);
            assertInstanceOf(AlertEvent.Fired.class, round.get(0));
            assertEquals(round, events, "listeners get the same events");
            assertTrue(log.text().contains("WARNING Alert #1 low-tps on lobby-1: tps 12 (rule tps < 18 for 1m)"),
                    log.text());

            ServiceSample latest = monitor.history(LOBBY).get(monitor.history(LOBBY).size() - 1);
            assertEquals(12.0, latest.tps());
            assertEquals(12.5, latest.cpu());
            assertEquals(900L << 20, latest.rss());
            assertEquals(7, monitor.history(LOBBY).size());
        }
    }

    @Test
    void staleStatusCountsAsMissing() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("stale-after: 30s\nrules:\n  - name: low-tps\n    when: tps < 18\n")) {
            monitor.statusReported(status(LOBBY, 20, 1, 2));
            clock.advanceSeconds(31);
            monitor.sampleNow();
            assertTrue(Double.isNaN(monitor.history(LOBBY).get(0).tps()), "a 31 s old report is not used");
            // Counter-check: within 30 s it is used.
            monitor.statusReported(status(LOBBY, 5, 1, 2));
            clock.advanceSeconds(30);
            assertEquals(1, monitor.sampleNow().size());
        }
    }

    /** A process probe that blocks until released, like tasklist on a busy Windows machine. */
    private final class BlockingSource implements MetricSource {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.Set<Long> asked = java.util.Set.of();

        @Override
        public java.util.Map<Long, ProcessMetrics> processes(java.util.Set<Long> pids) {
            calls.incrementAndGet();
            asked = java.util.Set.copyOf(pids);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return source.processes(pids);
        }

        @Override
        public net.vectolyn.cloud.monitor.metric.NodeSample node(Instant at) {
            return source.node(at);
        }
    }

    @Test
    void aProbeSlowerThanStaleAfterDoesNotHoldUpTheRounds() throws Exception {
        directory.running("lobby-1", "lobby", 100).running("survival-1", "survival", 101);
        source.processes.put(100L, new MetricSource.ProcessMetrics(12.5, 900L << 20));
        BlockingSource slow = new BlockingSource();
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.parse(
                "schema-version: 1\nstale-after: 30s\nrules: []\n".getBytes(StandardCharsets.UTF_8));
        Thread probe = new Thread(() -> { }, "unused");
        try (Monitor monitor = Monitor.builder(directory, slow, settings).clock(clock).build()) {
            probe = Thread.ofPlatform().daemon().start(monitor::probeNow);
            assertTrue(slow.entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(100L, 101L), slow.asked, "one probe for all processes");
            // The probe stays busy for 90 s of monitor time, three times stale-after.
            for (int round = 0; round < 9; round++) {
                monitor.statusReported(status(LOBBY, 20, 1, 2));
                List<AlertEvent> done = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(5), monitor::sampleNow, "round " + round + " waited for the probe");
                assertTrue(done.isEmpty());
                ServiceSample sample = monitor.history(LOBBY).getLast();
                assertEquals(clock.instant(), sample.at(), "dated when it was taken");
                assertEquals(3, sample.playersOnline(), "the bridge report is fresh in every round");
                assertTrue(Double.isNaN(sample.cpu()), "no process values before the first probe ends");
                clock.advanceSeconds(10);
            }
            assertEquals(1, slow.calls.get(), "the rounds did not start probes of their own");

            slow.release.countDown();
            probe.join(10_000);
            monitor.statusReported(status(LOBBY, 20, 1, 2));
            monitor.sampleNow();
            assertEquals(12.5, monitor.history(LOBBY).getLast().cpu(), "the finished probe is used");

            // A probe result older than stale-after counts as missing, like an old bridge report.
            clock.advanceSeconds(31);
            monitor.statusReported(status(LOBBY, 20, 1, 2));
            monitor.sampleNow();
            assertTrue(Double.isNaN(monitor.history(LOBBY).getLast().cpu()));
            assertEquals(ServiceSample.UNKNOWN, monitor.history(LOBBY).getLast().rss());
        } finally {
            slow.release.countDown();
            probe.join(10_000);
        }
    }

    @Test
    void theRunningMonitorSamplesWhileItsProbeIsStuck() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        BlockingSource stuck = new BlockingSource();
        // A real clock and the monitor's own threads: rounds every second, stale after 2 s, and a
        // probe that does not end while the test runs.
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.parse(
                "schema-version: 1\nsample-interval: 1s\nstale-after: 2s\nrules: []\n".getBytes(StandardCharsets.UTF_8));
        try (Monitor monitor = Monitor.builder(directory, stuck, settings).build()) {
            monitor.start();
            assertTrue(stuck.entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "the probe started");
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            int withPlayers = 0;
            while (withPlayers < 3 && System.nanoTime() < end) {
                monitor.statusReported(status(LOBBY, 20, 1, 2));
                withPlayers = (int) monitor.history(LOBBY).stream().filter(s -> s.playersOnline() == 3).count();
                Thread.sleep(100);
            }
            assertTrue(withPlayers >= 3, "three rounds with player counts while the probe hangs: "
                    + monitor.history(LOBBY));
            assertEquals(1, stuck.calls.get(), "still the first probe");
        } finally {
            stuck.release.countDown();
        }
    }

    @Test
    void anErrorInOneRoundDoesNotStopTheSamplingOrTheProbe() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        // A class missing from a runtime image: an Error, not an Exception. A periodic task that
        // throws is never run again, so the samples would age and the scaler would see no counts.
        java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        MetricSource failing = new MetricSource() {
            @Override
            public java.util.Map<Long, ProcessMetrics> processes(java.util.Set<Long> pids) {
                if (probes.incrementAndGet() == 1) {
                    throw new NoClassDefFoundError("com/sun/management/OperatingSystemMXBean");
                }
                return source.processes(pids);
            }

            @Override
            public net.vectolyn.cloud.monitor.metric.NodeSample node(Instant at) {
                if (rounds.incrementAndGet() == 1) {
                    throw new NoClassDefFoundError("com/sun/management/OperatingSystemMXBean");
                }
                return source.node(at);
            }
        };
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.parse(
                "schema-version: 1\nsample-interval: 1s\nrules: []\n".getBytes(StandardCharsets.UTF_8));
        try (Fakes.LogCapture log = new Fakes.LogCapture("net.vectolyn.cloud.monitor");
             Monitor monitor = Monitor.builder(directory, failing, settings).build()) {
            monitor.start();
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while ((rounds.get() < 2 || probes.get() < 2) && System.nanoTime() < end) {
                Thread.sleep(50);
            }
            assertTrue(rounds.get() >= 2, "the round after the failed one ran");
            assertTrue(probes.get() >= 2, "the probe after the failed one ran");
            assertTrue(log.text().contains("Monitor sample failed"), "the failure is logged");
            assertTrue(log.text().contains("Monitor process probe failed"), "the failure is logged");
        }
    }

    @Test
    void theLastBridgeReportIsKnownUntilTheServiceStops() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("rules: []\n")) {
            assertEquals(java.util.Optional.empty(), monitor.lastStatusReport(LOBBY));
            assertEquals(java.util.Optional.empty(), monitor.latest(LOBBY));
            monitor.statusReported(status(LOBBY, 20, 1, 2));
            assertEquals(java.util.Optional.of(T0), monitor.lastStatusReport(LOBBY));
            monitor.sampleNow();
            assertEquals(T0, monitor.latest(LOBBY).orElseThrow().at());
            monitor.serviceStateChanged(LOBBY, ServicePhase.STOPPED, ServicePhase.RUNNING);
            assertEquals(java.util.Optional.empty(), monitor.lastStatusReport(LOBBY), "a report of the old process");
        }
    }

    @Test
    void proxiesHaveNoTpsWhateverTheyReport() throws Exception {
        directory.put("proxy-1", "proxy", true, ServicePhase.RUNNING, 7, 0);
        ServiceId proxy = new ServiceId("proxy-1");
        try (Monitor monitor = monitor("rules:\n  - name: low-tps\n    when: tps < 18\n")) {
            monitor.statusReported(new ServiceStatus(proxy, 10, 500, 0, 0, 1, 2));
            assertTrue(monitor.sampleNow().isEmpty());
            ServiceSample sample = monitor.history(proxy).get(0);
            assertTrue(Double.isNaN(sample.tps()));
            assertEquals(10, sample.playersOnline());
        }
    }

    @Test
    void heapRuleNeedsAKnownMaximum() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("rules:\n  - name: heap\n    when: heap > 90%\n")) {
            monitor.statusReported(status(LOBBY, 20, 1_000, ServiceStatus.UNKNOWN_HEAP_MAX));
            assertTrue(monitor.sampleNow().isEmpty(), "heap max -1: not measured, no alert");
            monitor.statusReported(status(LOBBY, 20, 950, 1_000));
            assertEquals(1, monitor.sampleNow().size());
        }
    }

    @Test
    void gaugeAlertsEndWhenTheServiceStopsCrashAlertsDoNot() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("rules:\n  - name: low-tps\n    when: tps < 18\n"
                + "  - name: crash-loop\n    when: crashes >= 2 within 10m\n")) {
            monitor.statusReported(status(LOBBY, 5, 1, 2));
            assertEquals(1, monitor.sampleNow().size());
            monitor.serviceStateChanged(LOBBY, ServicePhase.CRASHED, ServicePhase.RUNNING);
            monitor.serviceStateChanged(LOBBY, ServicePhase.PREPARED, ServicePhase.CRASHED);
            monitor.serviceStateChanged(LOBBY, ServicePhase.STARTING, ServicePhase.PREPARED);
            monitor.serviceStateChanged(LOBBY, ServicePhase.CRASHED, ServicePhase.STARTING);
            directory.put("lobby-1", "lobby", false, ServicePhase.CRASHED, 0, 2);
            clock.advanceSeconds(10);
            List<AlertEvent> round = monitor.sampleNow();
            assertEquals(2, round.size(), round.toString());
            AlertEvent.Resolved ended = assertInstanceOf(AlertEvent.Resolved.class, round.get(0));
            assertEquals(Alert.Resolution.SERVICE_GONE, ended.alert().resolution().orElseThrow());
            AlertEvent.Fired loop = assertInstanceOf(AlertEvent.Fired.class, round.get(1));
            assertEquals("crash-loop", loop.alert().rule());
            assertEquals(2, monitor.services().get(0).crashes());

            clock.advanceSeconds(600);
            AlertEvent.Resolved cleared = assertInstanceOf(AlertEvent.Resolved.class, monitor.sampleNow().get(0));
            assertEquals("crash-loop", cleared.alert().rule(), "the crashes left the window");
        }
    }

    @Test
    void aStatusFromTheOldProcessDoesNotCountForTheNewOne() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("rules: []\n")) {
            monitor.statusReported(status(LOBBY, 20, 1, 2));
            monitor.serviceStateChanged(LOBBY, ServicePhase.STOPPING, ServicePhase.RUNNING);
            monitor.serviceStateChanged(LOBBY, ServicePhase.STOPPED, ServicePhase.STOPPING);
            monitor.sampleNow();
            assertTrue(Double.isNaN(monitor.history(LOBBY).get(0).tps()));
        }
    }

    @Test
    void goneServicesLoseTheirAlertsAndHistory() throws Exception {
        directory.running("lobby-2", "lobby", 100);
        ServiceId id = new ServiceId("lobby-2");
        try (Monitor monitor = monitor("rules:\n  - name: low-tps\n    when: tps < 18\n")) {
            monitor.statusReported(status(id, 5, 1, 2));
            assertEquals(1, monitor.sampleNow().size());
            directory.remove("lobby-2");
            AlertEvent.Resolved gone = assertInstanceOf(AlertEvent.Resolved.class, monitor.sampleNow().get(0));
            assertEquals(Alert.Resolution.SERVICE_GONE, gone.alert().resolution().orElseThrow());
            assertTrue(monitor.history(id).isEmpty());
        }
    }

    @Test
    void nodeRulesUseTheNodeSample() throws Exception {
        source.diskFree = 4L << 30;
        try (Monitor monitor = monitor("")) {
            List<AlertEvent> round = monitor.sampleNow();
            assertEquals(1, round.size(), "default rules: low-disk");
            assertEquals("low-disk", round.get(0).alert().rule());
            source.diskFree = (long) (5.5 * (1L << 30));
            assertTrue(monitor.sampleNow().isEmpty(), "5.5 GB is between threshold and clear value");
            source.diskFree = 6L << 30;
            assertInstanceOf(AlertEvent.Resolved.class, monitor.sampleNow().get(0));
            assertEquals(3, monitor.nodeHistory().size());
        }
    }

    @Test
    void historyIsARingBufferOfTheConfiguredSize() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("history:\n  samples: 10\nrules: []\n")) {
            for (int i = 0; i < 25; i++) {
                step(monitor, status(LOBBY, 20, 1, 2), 10);
            }
            List<ServiceSample> history = monitor.history(LOBBY);
            assertEquals(10, history.size());
            assertEquals(T0.plusSeconds(150), history.get(0).at());
            assertEquals(10, monitor.nodeHistory().size());
        }
    }

    @Test
    void onlyRunningProcessesAreMeasured() throws Exception {
        directory.running("lobby-1", "lobby", 100).put("lobby-2", "lobby", false, ServicePhase.STOPPED, 0, 0)
                .put("lobby-3", "lobby", false, ServicePhase.PREPARED, 0, 0);
        try (Monitor monitor = monitor("rules: []\n")) {
            monitor.probeNow();
            monitor.sampleNow();
            assertEquals(List.of(java.util.Set.of(100L)), source.asked, "one probe call, only for running processes");
            assertTrue(monitor.history(new ServiceId("lobby-2")).isEmpty(), "no samples without a process");
            assertEquals(3, monitor.services().size());
        }
    }

    @Test
    void historySurvivesARestartWhenPersisted(@TempDir Path home) throws Exception {
        directory.running("lobby-1", "lobby", 100);
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.parse(
                "schema-version: 1\nhistory:\n  persist: true\nrules: []\n".getBytes(StandardCharsets.UTF_8));
        HistoryStore store = new HistoryStore(home, java.time.Duration.ofHours(24), 360);
        try (Monitor first = Monitor.builder(directory, source, settings).clock(clock).historyStore(store).build()) {
            step(first, status(LOBBY, 19.5, 1, 2), 10);
            step(first, status(LOBBY, 18.5, 1, 2), 10);
        }
        assertTrue(Files.isRegularFile(store.file()), "written on close");
        try (Monitor second = Monitor.builder(directory, source, settings).clock(clock).historyStore(store).build()) {
            second.start();
            List<ServiceSample> restored = second.history(LOBBY);
            assertEquals(2, restored.size());
            assertEquals(18.5, restored.get(1).tps(), 1e-6);
            assertEquals(2, second.nodeHistory().size());
        }
        assertFalse(Files.exists(home.resolve("state").resolve("services.json")));
    }

    @Test
    void disabledMonitorDoesNotStart() throws Exception {
        try (Monitor monitor = monitor("enabled: false\n")) {
            monitor.start();
            clock.advanceSeconds(60);
            assertTrue(monitor.nodeHistory().isEmpty());
        }
    }

    @Test
    void acknowledgeGoesToTheListeners() throws Exception {
        directory.running("lobby-1", "lobby", 100);
        try (Monitor monitor = monitor("rules:\n  - name: low-tps\n    when: tps < 18\n")) {
            monitor.statusReported(status(LOBBY, 5, 1, 2));
            int id = monitor.sampleNow().get(0).alert().id();
            assertFalse(monitor.acknowledge(id + 7));
            assertTrue(monitor.acknowledge(id));
            assertInstanceOf(AlertEvent.Acknowledged.class, events.get(events.size() - 1));
            assertEquals(0, monitor.acknowledgeAll());
        }
    }
}
