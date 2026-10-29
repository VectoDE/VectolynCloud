package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.config.model.Language;
import org.junit.jupiter.api.Test;

class SetupEnvironmentTest {

    private static final Map<String, String> WINGS = Map.of("STARTUP", "java -jar VectoraCloud.jar",
            "SERVER_MEMORY", "6144", "SERVER_PORT", "25600");

    @Test
    void launcherSettingsAreTaken() {
        SetupEnvironment env = SetupEnvironment.of(Map.of("language", "de", "non-interactive", "true",
                "setup-file", "setup.yml", "platform.os", "windows 11", "platform.pterodactyl", "false"), Map.of());
        assertEquals(Language.DE, env.language());
        assertFalse(env.interactive());
        assertEquals(Optional.of(Path.of("setup.yml")), env.setupFile());
        assertTrue(env.windows());
        assertFalse(env.pterodactyl());
        assertFalse(env.container());
    }

    @Test
    void defaultsAreEnglishAndInteractive() {
        SetupEnvironment env = SetupEnvironment.of(Map.of("platform.os", "linux", "setup-file", " "), Map.of());
        assertEquals(Language.EN, env.language());
        assertTrue(env.interactive());
        assertEquals(Optional.empty(), env.setupFile());
        assertFalse(env.windows());
    }

    @Test
    void pterodactylIsDetectedLikeTheLauncherDoes() {
        assertTrue(SetupEnvironment.isPterodactyl(WINGS));
        assertTrue(SetupEnvironment.isPterodactyl(Map.of("P_SERVER_UUID", "abc")));
        assertFalse(SetupEnvironment.isPterodactyl(Map.of("STARTUP", "x", "SERVER_MEMORY", "1")));
        assertFalse(SetupEnvironment.isPterodactyl(Map.of("P_SERVER_UUID", " ")));

        SetupEnvironment env = SetupEnvironment.of(Map.of(), WINGS);
        assertTrue(env.pterodactyl());
        assertTrue(env.container());
        assertEquals(OptionalLong.of(6144), env.serverMemory());
        assertEquals(OptionalInt.of(25600), env.serverPort());
    }

    @Test
    void launcherDecisionWinsOverTheEnvironment() {
        SetupEnvironment env = SetupEnvironment.of(Map.of("platform.pterodactyl", "false"), WINGS);
        assertFalse(env.pterodactyl());
        assertEquals(OptionalInt.empty(), env.serverPort(), "SERVER_PORT only counts on Pterodactyl");
    }

    @Test
    void oddNumbersAreIgnored() {
        for (String value : new String[] {"0", "65536", "-1", "25 565", "999999", ""}) {
            SetupEnvironment env = SetupEnvironment.of(Map.of(),
                    Map.of("P_SERVER_UUID", "x", "SERVER_PORT", value, "SERVER_MEMORY", value.equals("999999") ? "x" : value));
            assertEquals(OptionalInt.empty(), env.serverPort(), value);
        }
        assertEquals(OptionalLong.empty(), SetupEnvironment.of(Map.of(), Map.of("SERVER_MEMORY", "0")).serverMemory());
        assertEquals(OptionalLong.empty(), SetupEnvironment.of(Map.of(), Map.of("SERVER_MEMORY", "1e9")).serverMemory());
    }
}
