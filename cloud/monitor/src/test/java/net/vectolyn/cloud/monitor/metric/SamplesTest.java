package net.vectolyn.cloud.monitor.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class SamplesTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00.750Z");

    @Test
    void serviceValuesAndUnknowns() {
        ServiceSample s = new ServiceSample(T0, 3, 100, 19.5, 12, 900, 1000, 4, 2048);
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), s.at(), "whole seconds");
        assertEquals(OptionalDouble.of(90.0), s.value(Metric.HEAP));
        assertEquals(OptionalDouble.of(3), s.value(Metric.PLAYERS));
        assertEquals(OptionalDouble.empty(), s.value(Metric.CRASHES), "not part of a sample");
        ServiceSample unknown = new ServiceSample(T0, -5, -1, Double.POSITIVE_INFINITY, -1, 900, 0, Double.NaN, -9);
        assertEquals(ServiceSample.UNKNOWN, unknown.playersOnline());
        assertEquals(OptionalDouble.empty(), unknown.value(Metric.TPS));
        assertEquals(OptionalDouble.empty(), unknown.value(Metric.MSPT));
        assertEquals(OptionalDouble.empty(), unknown.value(Metric.HEAP), "heap max 0 means unknown");
        assertEquals(OptionalDouble.of(900), unknown.value(Metric.HEAP_USED));
        assertEquals(OptionalDouble.empty(), unknown.value(Metric.RSS));
        assertEquals(OptionalDouble.empty(), ServiceSample.empty(T0).value(Metric.CPU));
    }

    @Test
    void nodeValues() {
        NodeSample n = new NodeSample(T0, 250, 3, 4, 10, 20);
        assertEquals(100.0, n.cpu(), "capped");
        assertEquals(OptionalDouble.of(75.0), n.value(Metric.NODE_MEMORY));
        assertEquals(OptionalDouble.of(10), n.value(Metric.DISK_FREE));
        assertEquals(OptionalDouble.empty(), NodeSample.empty(T0).value(Metric.NODE_MEMORY));
    }

    @Test
    void ringBufferKeepsTheNewest() {
        RingBuffer<Integer> ring = new RingBuffer<>(3);
        assertEquals(null, ring.latest());
        for (int i = 1; i <= 5; i++) {
            ring.add(i);
        }
        assertEquals(List.of(3, 4, 5), ring.snapshot());
        assertEquals(5, ring.latest());
        assertEquals(3, ring.size());
        ring.clear();
        assertTrue(ring.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<>(0));
        assertThrows(NullPointerException.class, () -> ring.add(null));
    }

    @Test
    void unitsFormat() {
        assertEquals("1.5 GiB", MetricUnit.bytes(1.5 * (1L << 30)));
        assertEquals("512 B", MetricUnit.bytes(512));
        assertEquals("-", MetricUnit.bytes(-1));
        assertEquals("91%", MetricUnit.PERCENT.format(91.2));
        assertEquals("18", MetricUnit.NUMBER.format(18));
        assertEquals("18.5", MetricUnit.NUMBER.format(18.5));
        assertEquals("-", MetricUnit.NUMBER.format(Double.NaN));
        assertEquals("2.5 ms", MetricUnit.MILLISECONDS.format(2.5));
    }
}
