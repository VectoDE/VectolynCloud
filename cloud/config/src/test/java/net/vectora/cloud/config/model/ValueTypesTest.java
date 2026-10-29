package net.vectora.cloud.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Test
    void memorySizeParsesMegabytesAndGigabytes() {
        assertEquals(512, MemorySize.parse("512M").megabytes());
        assertEquals(2048, MemorySize.parse("2g").megabytes());
        assertEquals("2G", MemorySize.ofMegabytes(2048).toString());
        assertEquals("1536M", MemorySize.ofMegabytes(1536).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "512", "0M", "-1M", "512 M", "512MB", "9999999G", "1.5G"})
    void memorySizeRejectsMalformedText(String text) {
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse(text));
    }

    @Test
    void timeSpanUsesTheLargestEvenUnit() {
        assertEquals(Duration.ofMinutes(5), TimeSpan.parse("5m").duration());
        assertEquals("5m", TimeSpan.parse("300s").toString());
        assertEquals("1h", TimeSpan.parse("60m").toString());
        assertEquals("0s", TimeSpan.parse("0m").toString());
        assertEquals("90s", TimeSpan.ofSeconds(90).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "5", "5d", "05m", "-1s", "169h", "1.5h"})
    void timeSpanRejectsMalformedOrTooLongValues(String text) {
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parse(text));
    }

    @Test
    void javaSelectionIsAutoOrAMajorVersion() {
        assertTrue(JavaSelection.parse("auto").isAuto());
        assertEquals(21, JavaSelection.parse("21").major());
        assertFalse(JavaSelection.of(8).isAuto());
        assertEquals("auto", JavaSelection.AUTO.fileValue());
        assertEquals(25, JavaSelection.of(25).fileValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Auto", "7", "0", "021", "100", "21.0"})
    void javaSelectionRejectsOtherValues(String text) {
        assertThrows(IllegalArgumentException.class, () -> JavaSelection.parse(text));
    }

    @Test
    void wantedServersSwitchesExactlyOneServer() {
        WantedServers none = WantedServers.none();
        for (ServerKind kind : ServerKind.values()) {
            WantedServers one = none.with(kind, true);
            for (ServerKind other : ServerKind.values()) {
                assertEquals(other == kind, one.isWanted(other), kind + " / " + other);
            }
        }
        assertEquals(9, WantedServers.all().asMap().size());
        assertTrue(WantedServers.none().asMap().values().stream().noneMatch(Boolean::booleanValue));
    }

    @Test
    void portRangeIsInclusive() {
        PortRange range = new PortRange(30000, 30999);
        assertTrue(range.contains(30000));
        assertTrue(range.contains(30999));
        assertFalse(range.contains(31000));
    }
}
