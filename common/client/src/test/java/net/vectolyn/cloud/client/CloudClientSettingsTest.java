package net.vectolyn.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.vectolyn.protocol.AuthToken;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;

class CloudClientSettingsTest {

    private static final String TOKEN = "service-token-not-a-real-secret-01";
    private static final UnaryOperator<String> NONE = name -> null;

    private static UnaryOperator<String> of(Map<String, String> values) {
        return values::get;
    }

    @Test
    void readsTheFourVariablesTheCloudSets() {
        CloudClientSettings settings = CloudClientSettings.from(of(Map.of(
                "VECTOLYN_CLOUD_HOST", "10.0.0.5",
                "VECTOLYN_CLOUD_PORT", "7011",
                "VECTOLYN_SERVICE_ID", "lobby-1",
                "VECTOLYN_SERVICE_TOKEN", TOKEN)), NONE);
        assertEquals("10.0.0.5", settings.host());
        assertEquals(7011, settings.port());
        assertEquals(new ServiceId("lobby-1"), settings.service());
        assertTrue(settings.token().matches(new AuthToken(TOKEN)));
    }

    @Test
    void hostAndPortDefaultToTheLocalCloud() {
        CloudClientSettings settings = CloudClientSettings.from(of(Map.of(
                "VECTOLYN_SERVICE_ID", "lobby-1", "VECTOLYN_SERVICE_TOKEN", TOKEN)), NONE);
        assertEquals("127.0.0.1", settings.host());
        assertEquals(7010, settings.port());
    }

    @Test
    void systemPropertiesWinOverTheEnvironment() {
        CloudClientSettings settings = CloudClientSettings.from(
                of(Map.of("VECTOLYN_SERVICE_ID", "lobby-1", "VECTOLYN_SERVICE_TOKEN", TOKEN, "VECTOLYN_CLOUD_PORT", "7010")),
                of(Map.of("vectolyn.service.id", "lobby-2", "vectolyn.cloud.port", "7020")));
        assertEquals(new ServiceId("lobby-2"), settings.service());
        assertEquals(7020, settings.port());
    }

    @Test
    void blankValuesCountAsUnset() {
        CloudClientSettings settings = CloudClientSettings.from(
                of(Map.of("VECTOLYN_SERVICE_ID", "lobby-1", "VECTOLYN_SERVICE_TOKEN", TOKEN)),
                of(Map.of("vectolyn.service.id", "  ")));
        assertEquals(new ServiceId("lobby-1"), settings.service());
    }

    @Test
    void missingOrInvalidValuesNameTheVariable() {
        IllegalStateException noId = assertThrows(IllegalStateException.class,
                () -> CloudClientSettings.from(of(Map.of("VECTOLYN_SERVICE_TOKEN", TOKEN)), NONE));
        assertTrue(noId.getMessage().contains("VECTOLYN_SERVICE_ID"));

        IllegalStateException noToken = assertThrows(IllegalStateException.class,
                () -> CloudClientSettings.from(of(Map.of("VECTOLYN_SERVICE_ID", "lobby-1")), NONE));
        assertTrue(noToken.getMessage().contains("VECTOLYN_SERVICE_TOKEN"));

        IllegalStateException badPort = assertThrows(IllegalStateException.class, () -> CloudClientSettings.from(
                of(Map.of("VECTOLYN_SERVICE_ID", "lobby-1", "VECTOLYN_SERVICE_TOKEN", TOKEN, "VECTOLYN_CLOUD_PORT", "70000")),
                NONE));
        assertTrue(badPort.getMessage().contains("port"));

        assertThrows(IllegalStateException.class, () -> CloudClientSettings.from(
                of(Map.of("VECTOLYN_SERVICE_ID", "Lobby 1", "VECTOLYN_SERVICE_TOKEN", TOKEN)), NONE));
    }

    @Test
    void anInvalidTokenIsNeverEchoed() {
        String shortSecret = "short-secret";
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> CloudClientSettings.from(
                of(Map.of("VECTOLYN_SERVICE_ID", "lobby-1", "VECTOLYN_SERVICE_TOKEN", shortSecret)), NONE));
        assertTrue(error.getMessage().contains("VECTOLYN_SERVICE_TOKEN"));
        assertFalse(error.getMessage().contains(shortSecret));
    }

    @Test
    void toStringHidesTheToken() {
        CloudClientSettings settings = CloudClientSettings.of("127.0.0.1", 7010, new ServiceId("lobby-1"),
                new AuthToken(TOKEN));
        assertFalse(settings.toString().contains(TOKEN));
    }

    @Test
    void timeoutsAreChecked() {
        CloudClientSettings base = CloudClientSettings.of("127.0.0.1", 7010, new ServiceId("lobby-1"), new AuthToken(TOKEN));
        Duration second = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class,
                () -> base.withTimeouts(second, Duration.ofSeconds(30), Duration.ofSeconds(30), second, second, second));
        assertThrows(IllegalArgumentException.class,
                () -> base.withTimeouts(second, second, Duration.ofSeconds(5), second, Duration.ofSeconds(2), second));
        assertThrows(IllegalArgumentException.class,
                () -> base.withTimeouts(Duration.ZERO, second, Duration.ofSeconds(5), second, second, second));
        assertThrows(IllegalArgumentException.class,
                () -> CloudClientSettings.of(" ", 7010, new ServiceId("lobby-1"), new AuthToken(TOKEN)));
    }
}
