package net.vectora.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import net.vectora.cloud.config.model.ApiConfig;
import net.vectora.cloud.config.model.TlsConfig;
import net.vectora.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;

class ApiSettingsTest {

    private static final ApiTls.Material MATERIAL = new ApiTls.Material(Path.of("api-keystore.p12"),
            Secret.of("keystore-password-not-real"), "AB:CD", true);

    @Test
    void tlsInTheConfigAndKeyMaterialMustAgree() {
        ApiConfig plain = ApiConfig.defaults().withTls(TlsConfig.disabled());
        ApiConfig tls = ApiConfig.defaults().withTls(new TlsConfig(true, null));

        // TLS switched on but no material: the server would silently speak plain HTTP.
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ApiSettings.of(tls, Optional.empty()));
        assertTrue(missing.getMessage().contains("api.tls.enabled"), missing.getMessage());
        // Material for a config that has TLS off: someone passed the wrong thing.
        assertThrows(IllegalArgumentException.class, () -> ApiSettings.of(plain, Optional.of(MATERIAL)));

        // Counter-check: both ways that agree work.
        assertEquals(Optional.of(MATERIAL), ApiSettings.of(tls, Optional.of(MATERIAL)).tls());
        assertEquals(Optional.empty(), ApiSettings.of(plain, Optional.empty()).tls());
        assertEquals(Optional.empty(), ApiSettings.of(ApiConfig.defaults().withTls(null), Optional.empty()).tls());
    }
}
