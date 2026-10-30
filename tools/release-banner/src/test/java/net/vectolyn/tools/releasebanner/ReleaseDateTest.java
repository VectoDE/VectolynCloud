package net.vectolyn.tools.releasebanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseDateTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2026-10-30|Oct 30, 2026",
        "2026-01-01|Jan 1, 2026",
        "2027-02-28|Feb 28, 2027",
        "2028-02-29|Feb 29, 2028",
        "2026-05-09|May 9, 2026",
        "2026-12-31|Dec 31, 2026",
    })
    void formatsInEnglish(String iso, String shown) {
        assertEquals(shown, ReleaseDate.format(ReleaseDate.parse(iso)));
    }

    @Test
    void ignoresTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("Mar 3, 2026", ReleaseDate.format(LocalDate.of(2026, 3, 3)));
        } finally {
            Locale.setDefault(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "2026-10-3", "26-10-30", "2026/10/30", "30.10.2026", "2026-13-01", "2026-02-30", "2027-02-29",
        "2026-10-30T12:00:00Z", " 2026-10-30", "+2026-10-30", "2026-1O-30", "２026-10-30",
    })
    void rejectsAnythingButIsoDates(String iso) {
        assertThrows(IllegalArgumentException.class, () -> ReleaseDate.parse(iso));
    }

    @Test
    void rejectsMissingDate() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseDate.parse(null));
    }
}
