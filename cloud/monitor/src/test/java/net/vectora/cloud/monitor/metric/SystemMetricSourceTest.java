package net.vectora.cloud.monitor.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import net.vectora.cloud.monitor.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemMetricSourceTest {

    @Test
    void cpuPercentIsShareOfTheWholeMachine() {
        // 2 s of CPU in 1 s on 4 cores = 50 %.
        assertEquals(50.0, SystemMetricSource.cpuPercent(0, 2_000_000_000L, 0, 1_000_000_000L, 4), 1e-9);
        assertEquals(100.0, SystemMetricSource.cpuPercent(0, 9_000_000_000L, 0, 1_000_000_000L, 4), "capped");
        assertTrue(Double.isNaN(SystemMetricSource.cpuPercent(5, 4, 0, 1_000_000_000L, 4)), "counter went back");
        assertTrue(Double.isNaN(SystemMetricSource.cpuPercent(0, 1, 7, 7, 4)), "no time passed");
    }

    @Test
    void meminfoIsUsedOnlyWithoutAContainerLimit() {
        String text = "MemTotal:       16000000 kB\nMemFree:  1000000 kB\nMemAvailable:    8000000 kB\n";
        long total = 16_000_000L * 1024;
        assertEquals(OptionalLong.of(8_000_000L * 1024), SystemMetricSource.parseMeminfo(text, total));
        assertEquals(OptionalLong.empty(), SystemMetricSource.parseMeminfo(text, 2L << 30), "container limit differs");
        assertEquals(OptionalLong.empty(), SystemMetricSource.parseMeminfo("MemTotal: 1 kB\n", 1024));
        assertEquals(OptionalLong.empty(), SystemMetricSource.parseMeminfo("garbage", total));
    }

    @Test
    void measuresThisProcessAndTheNode(@TempDir Path home) throws Exception {
        SystemMetricSource source = SystemMetricSource.forHome(home);
        long self = ProcessHandle.current().pid();
        MetricSource.ProcessMetrics first = source.processes(Set.of(self)).get(self);
        assertTrue(Double.isNaN(first.cpu()), "no previous reading yet");
        long until = System.nanoTime() + 200_000_000L;
        double sink = 0;
        while (System.nanoTime() < until) {
            sink += Math.sqrt(sink + 1);
        }
        MetricSource.ProcessMetrics second = source.processes(Set.of(self)).get(self);
        assertTrue(sink > 0);
        if (ProcessHandle.current().info().totalCpuDuration().isPresent()) {
            assertTrue(second.cpu() >= 0 && second.cpu() <= 100, "cpu " + second.cpu());
        }
        NodeSample node = source.node(Instant.now());
        assertTrue(node.diskFree() > 0, "free space of the temp folder");
        assertTrue(node.diskTotal() >= node.diskFree());
        assertTrue(node.memoryTotal() > 0);
        assertTrue(node.memoryUsed() >= 0 && node.memoryUsed() <= node.memoryTotal());
    }

    @Test
    void unknownPidsAndFailingProbesGiveUnknownValues(@TempDir Path home) {
        ResidentMemoryProbe failing = new ResidentMemoryProbe() {
            @Override
            public Map<Long, Long> measure(Set<Long> pids) throws IOException {
                throw new IOException("tasklist took longer than 5 s");
            }

            @Override
            public String description() {
                return "failing";
            }
        };
        SystemMetricSource source = new SystemMetricSource(home, failing);
        try (Fakes.LogCapture log = new Fakes.LogCapture(SystemMetricSource.class.getName())) {
            MetricSource.ProcessMetrics gone = source.processes(Set.of(Long.MAX_VALUE)).get(Long.MAX_VALUE);
            assertTrue(Double.isNaN(gone.cpu()));
            assertEquals(ServiceSample.UNKNOWN, gone.rss());
            source.processes(Set.of(Long.MAX_VALUE));
            assertEquals(1, log.records.size(), "the failure is logged once, not every sample");
        }
        NodeSample missingHome = new SystemMetricSource(home.resolve("missing"), failing).node(Instant.now());
        assertEquals(NodeSample.UNKNOWN, missingHome.diskFree());
    }
}
