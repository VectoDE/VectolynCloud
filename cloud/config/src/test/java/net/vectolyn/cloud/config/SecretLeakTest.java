package net.vectolyn.cloud.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.operation.SetNetworkName;
import net.vectolyn.cloud.config.secret.EnvFormatException;
import net.vectolyn.cloud.config.secret.SecretStore;
import net.vectolyn.cloud.config.validation.ConfigValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A secret pasted into the wrong place must not come back through an error message, an exception
 * or a log line.
 */
class SecretLeakTest {

    // Distinctive marker, not a real secret. Not a valid name either: references to existing names
    // (unknown-group and so on) quote the name, which is fine because names are not secret.
    private static final String SECRET = "Canary.Canary.Canary";

    @TempDir
    Path root;

    @Test
    void notInParseErrorsOfConfigFiles() throws Exception {
        String[] files = {
            "schema-version: 1\ndatabase-password: " + SECRET + "\n",
            "schema-version: 1\nproxy:\n  port: " + SECRET + "\n",
            "schema-version: 1\nlanguage: " + SECRET + "\n",
            "schema-version: 1\nnetwork-name: [" + SECRET + "\n",
            "schema-version: " + SECRET + "\n",
        };
        for (String content : files) {
            Files.createDirectories(root.resolve("config"));
            Files.writeString(root.resolve("config/cloud.yml"), content);
            ConfigException e = assertThrows(ConfigException.class, () -> ConfigService.open(CloudHome.of(root)));
            assertClean(e);
        }
    }

    @Test
    void notInValidationErrors() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            ConfigException e = assertThrows(ConfigException.class, () -> service.apply(new SetNetworkName(SECRET + " with spaces!?")));
            assertClean(e);
        }
        List<ConfigError> errors = ConfigValidator.validate(ConfigSnapshot.defaults()
                .withCloud(ConfigSnapshot.defaults().cloud().withNodeId(SECRET)));
        errors.forEach(err -> assertFalse(err.toString().contains(SECRET) || err.describe(Language.DE).contains(SECRET)));
    }

    @Test
    void notInSecretFileErrors() throws Exception {
        Path env = root.resolve("vectolyn.env");
        Files.writeString(env, "VECTOLYN_DB_PASSWORD=" + SECRET + " \nlower=" + SECRET + "\n");
        EnvFormatException e = assertThrows(EnvFormatException.class, () -> new SecretStore(env).load());
        assertFalse(e.toString().contains(SECRET));
        assertFalse(e.describe(Language.DE).contains(SECRET));
    }

    private static void assertClean(ConfigException e) {
        assertFalse(e.toString().contains(SECRET), e.toString());
        assertFalse(e.describe(Language.EN).contains(SECRET), e.describe(Language.EN));
        assertFalse(e.describe(Language.DE).contains(SECRET));
        for (ConfigError error : e.errors()) {
            assertFalse(error.args().toString().contains(SECRET), error.toString());
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains(SECRET));
        }
    }
}
