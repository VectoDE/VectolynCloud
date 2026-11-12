// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.vectolyn.cloud.config.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PanelConfigLoaderTest {

    @Test
    void withoutAFileThePanelIsOff(@TempDir Path home) throws Exception {
        PanelConfigLoader.Settings settings = PanelConfigLoader.load(home);
        assertFalse(settings.config().enabled());
        assertEquals("127.0.0.1", settings.config().bind());
        assertEquals(8090, settings.config().port());
        assertEquals(Optional.empty(), settings.publicUrl());
    }

    @Test
    void theFileIsReadStrictly(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/panel.yml"),
                "schema-version: 1\nenabled: true\nport: 9000\npublic-url: https://panel.example.org\n");
        PanelConfigLoader.Settings settings = PanelConfigLoader.load(home);
        assertTrue(settings.config().enabled());
        assertEquals(9000, settings.config().port());
        assertEquals(Optional.of("https://panel.example.org"), settings.publicUrl());

        assertThrows(ConfigException.class, () -> PanelConfigLoader.parse(
                "schema-version: 1\nunknown: true\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ConfigException.class, () -> PanelConfigLoader.parse(
                "enabled: true\n".getBytes(StandardCharsets.UTF_8)), "schema-version is required");
    }

    @Test
    void portAndPublicUrlAreChecked() {
        ConfigException port = assertThrows(ConfigException.class, () -> PanelConfigLoader.parse(
                "schema-version: 1\nport: 70000\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("port", port.errors().get(0).path());
        for (String url : new String[] {"http://panel.example.org", "https://panel.example.org/panel",
                "https://user@panel.example.org", "ftp://localhost", "https://", "javascript:alert(1)"}) {
            assertTrue(PanelConfigLoader.origin(url).isEmpty(), url);
        }
        assertEquals(Optional.of("http://localhost:8090"), PanelConfigLoader.origin("http://localhost:8090"));
        assertEquals(Optional.of("https://panel.example.org"), PanelConfigLoader.origin("https://panel.example.org:443"));
    }
}
