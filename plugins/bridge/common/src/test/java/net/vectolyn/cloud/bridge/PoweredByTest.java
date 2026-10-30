package net.vectolyn.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoweredByTest {

    @Test
    void theSwitchIsOnByDefaultAndOffOnlyWhenTheCloudSaysSo() {
        assertTrue(PoweredBy.enabled(Map.of("VECTOLYN_POWERED_BY", "true")::get));
        assertTrue(PoweredBy.enabled(Map.of("VECTOLYN_POWERED_BY", " TRUE ")::get));
        assertTrue(PoweredBy.enabled(Map.<String, String>of()::get), "the cloud's default is on");
        assertTrue(PoweredBy.enabled(Map.of("VECTOLYN_POWERED_BY", " ")::get));
        // Counter-checks: false and anything that is not true switch it off.
        assertFalse(PoweredBy.enabled(Map.of("VECTOLYN_POWERED_BY", "false")::get));
        assertFalse(PoweredBy.enabled(Map.of("VECTOLYN_POWERED_BY", "no")::get));
    }

    @Test
    void theVersionComesFromTheCloudAndIsChecked() {
        assertEquals("0.2.0", PoweredBy.version(Map.of("VECTOLYN_CLOUD_VERSION", "0.2.0")::get));
        assertEquals("0.2.0-rc.1+build_7",
                PoweredBy.version(Map.of("VECTOLYN_CLOUD_VERSION", " 0.2.0-rc.1+build_7 ")::get));
        assertEquals("dev", PoweredBy.version(Map.<String, String>of()::get));
        // Counter-checks: nothing a player could be shown unchecked.
        assertEquals("dev", PoweredBy.version(Map.of("VECTOLYN_CLOUD_VERSION", "")::get));
        assertEquals("dev", PoweredBy.version(Map.of("VECTOLYN_CLOUD_VERSION", "1.0 <red>x")::get));
        assertEquals("dev", PoweredBy.version(Map.of("VECTOLYN_CLOUD_VERSION", "1".repeat(65))::get));
    }

    @Test
    void theAnswerIsEnglishUnlessTheClientIsGerman() {
        List<PoweredBy.Line> english = PoweredBy.lines("0.2.0", null);
        assertEquals(List.of("This network runs VectolynCloud 0.2.0",
                "Open source (GPL-3.0): github.com/VectoDE/VectolynCloud"),
                english.stream().map(PoweredBy.Line::text).toList());
        assertEquals(new PoweredBy.Line("This network runs ", "VectolynCloud 0.2.0"), english.getFirst());
        assertEquals("github.com/VectoDE/VectolynCloud", english.get(1).value());
        assertEquals(english, PoweredBy.lines("0.2.0", Locale.FRANCE));
        assertEquals(english, PoweredBy.lines("0.2.0", Locale.US));
        List<PoweredBy.Line> german = PoweredBy.lines("0.2.0", Locale.GERMANY);
        assertEquals(List.of("Dieses Netzwerk läuft mit VectolynCloud 0.2.0",
                "Open Source (GPL-3.0): github.com/VectoDE/VectolynCloud"),
                german.stream().map(PoweredBy.Line::text).toList());
        assertEquals(german, PoweredBy.lines("0.2.0", Locale.of("de", "AT")));
        assertEquals("https://github.com/VectoDE/VectolynCloud", PoweredBy.REPOSITORY_URL);
        assertEquals("version", PoweredBy.COMMAND);
    }
}
