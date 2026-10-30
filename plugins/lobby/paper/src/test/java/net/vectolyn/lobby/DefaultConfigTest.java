package net.vectolyn.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DefaultConfigTest {

    @Test
    void islandIsNotMarkedAsBuiltByDefault() throws IOException {
        // The lobby island is generated once on first start; a shipped "built: true" would skip that.
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml must be on the classpath");
            Map<String, Object> config = new Yaml().load(in);
            assertEquals(Boolean.FALSE, config.get("built"));
        }
    }
}
