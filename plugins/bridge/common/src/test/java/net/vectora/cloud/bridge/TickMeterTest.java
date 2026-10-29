package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TickMeterTest {

    private static final long TICK = 50_000_000L;

    @Test
    void beforeTicksItReportsAHealthyIdleServer() {
        TickMeter meter = new TickMeter();
        assertEquals(20.0, meter.tps());
        assertEquals(0.0, meter.mspt());
        meter.tick(0, 4);
        assertEquals(20.0, meter.tps());
        assertEquals(4.0, meter.mspt());
    }

    @Test
    void fullSpeedIsTwentyAndHalfSpeedIsTen() {
        TickMeter full = new TickMeter();
        for (int i = 0; i < 300; i++) {
            full.tick(i * TICK, 10);
        }
        assertEquals(20.0, full.tps(), 1e-9);
        assertEquals(10.0, full.mspt(), 1e-9);

        TickMeter slow = new TickMeter();
        for (int i = 0; i < 300; i++) {
            slow.tick(i * 2 * TICK, 100);
        }
        assertEquals(10.0, slow.tps(), 1e-9);
        assertEquals(100.0, slow.mspt(), 1e-9);
    }

    @Test
    void onlyTheWindowCountsAndBadDurationsAreZero() {
        TickMeter meter = new TickMeter();
        for (int i = 0; i < TickMeter.WINDOW; i++) {
            meter.tick(i * TICK, 200);
        }
        for (int i = 0; i < TickMeter.WINDOW; i++) {
            meter.tick((TickMeter.WINDOW + i) * TICK, i % 2 == 0 ? Double.NaN : -5);
        }
        assertEquals(0.0, meter.mspt(), 1e-9);
        assertEquals(20.0, meter.tps(), 1e-9);
    }

    @Test
    void fasterThanTwentyIsCapped() {
        TickMeter meter = new TickMeter();
        for (int i = 0; i < 10; i++) {
            meter.tick(i * TICK / 2, 1);
        }
        assertEquals(20.0, meter.tps());
        meter.tick(9 * TICK / 2, 1); // same time as the last tick
        assertEquals(20.0, meter.tps());
    }
}
