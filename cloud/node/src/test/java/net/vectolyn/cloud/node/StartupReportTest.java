package net.vectolyn.cloud.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StartupReportTest {

    private static final Path HOME = Path.of("vectolyn-home");

    @Test
    void bannerKeepsTheStartOutputFormat() {
        List<String> lines = StartupReport.banner("1.2.3", HOME, "120");
        int lettering = StartupReport.WIDE_LETTERING.size();
        assertEquals(lettering + 5, lines.size(), lines.toString());
        assertEquals("", lines.getFirst());
        assertEquals(StartupReport.WIDE_LETTERING, lines.subList(1, 1 + lettering));
        assertEquals("", lines.get(1 + lettering));
        assertTrue(lines.get(2 + lettering).startsWith("  VectolynCloud 1.2.3  |  Java "), lines.get(2 + lettering));
        assertEquals("  Home: " + HOME, lines.get(3 + lettering));
        assertEquals("", lines.getLast());
    }

    @Test
    void letteringIsPlainAsciiAndFitsItsWidth() {
        assertWithin(StartupReport.WIDE_LETTERING, 68);
        assertWithin(StartupReport.NARROW_LETTERING, 55);
        assertEquals("  __     __        _        _              ____ _                 _",
                StartupReport.WIDE_LETTERING.getFirst());
        assertEquals("     \\_/ \\___|\\___|\\__\\___/|_|\\__, |_| |_|\\____|_|\\___/ \\__,_|\\__,_|",
                StartupReport.WIDE_LETTERING.get(4));
        assertEquals("    \\_/\\___\\__|\\__\\___/_|\\_, |_||_\\___|_\\___/\\_,_\\__,_|",
                StartupReport.NARROW_LETTERING.get(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40", "69", " 55 "})
    void narrowConsolesGetTheNarrowLettering(String columns) {
        List<String> lines = StartupReport.banner("1.2.3", HOME, columns);
        assertEquals(StartupReport.NARROW_LETTERING, lines.subList(1, 1 + StartupReport.NARROW_LETTERING.size()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"70", "80", "", "wide", "0", "-5"})
    void anUnknownOrWideConsoleGetsTheWideLettering(String columns) {
        List<String> lines = StartupReport.banner("1.2.3", HOME, columns);
        assertEquals(StartupReport.WIDE_LETTERING, lines.subList(1, 1 + StartupReport.WIDE_LETTERING.size()));
    }

    private static void assertWithin(List<String> lettering, int width) {
        for (String line : lettering) {
            assertTrue(line.length() <= width, line);
            assertTrue(line.chars().allMatch(c -> c >= 0x20 && c < 0x7f), line);
            assertEquals(line.stripTrailing(), line, "no trailing spaces");
        }
    }
}
