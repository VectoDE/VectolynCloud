// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Optional;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;
import net.vectolyn.cloud.panel.http.RequestTarget;

/** Fuzz targets for what reaches the panel from outside before any check of a session. */
class PanelInputFuzzTest {

    @FuzzTest
    void requestTargetsAreCleanOrRefused(FuzzedDataProvider data) {
        String uri = data.consumeRemainingAsString();
        Optional<RequestTarget> target = RequestTarget.parse(uri);
        if (target.isEmpty()) {
            return;
        }
        String path = target.get().path();
        assertTrue(path.startsWith("/"), path);
        assertFalse(path.contains("//") || path.contains("%") || path.contains("\\"), path);
        String file = target.get().file();
        assertFalse(file.startsWith("/"), file);
        // A name such as "..x" is a plain file name; only a whole "." or ".." segment could leave a folder.
        for (String segment : file.split("/", -1)) {
            assertFalse(segment.isEmpty() || segment.equals(".") || segment.equals(".."), file);
        }
        target.get().query().forEach((key, value) -> assertFalse(key.isEmpty() || value.contains("=")));
    }

    @FuzzTest
    void filePathsStayInsideTheServiceFolder(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();
        java.util.List<String> parts;
        try {
            parts = net.vectolyn.cloud.panel.files.ServiceFiles.parsePath(text);
        } catch (net.vectolyn.cloud.panel.files.ServiceFiles.FileException e) {
            assertTrue(e.status() == 400, e.code());
            return;
        }
        assertTrue(text.length() <= 512, text);
        assertTrue(String.join("/", parts).equals(text), text);
        for (String part : parts) {
            assertFalse(part.isEmpty() || part.equals(".") || part.equals(".."), text);
            assertFalse(part.chars().anyMatch(c -> c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0), text);
            assertFalse(part.endsWith(".") || part.endsWith(" "), text);
        }
        java.nio.file.Path root = java.nio.file.Path.of("root");
        java.nio.file.Path resolved = root;
        for (String part : parts) {
            try {
                resolved = resolved.resolve(part);
            } catch (java.nio.file.InvalidPathException e) {
                return;
            }
        }
        assertTrue(resolved.normalize().startsWith(root), text);
    }

    @FuzzTest
    void panelYmlIsSettingsOrConfigErrors(byte[] content) {
        try {
            PanelConfigLoader.Settings settings = PanelConfigLoader.parse(content);
            assertTrue(settings.config().port() >= 1 && settings.config().port() <= 65_535);
        } catch (ConfigException e) {
            assertFalse(e.errors().isEmpty());
        }
    }

    @FuzzTest
    void publicUrlsAreOriginsOrRefused(FuzzedDataProvider data) {
        PanelConfigLoader.origin(data.consumeRemainingAsString()).ifPresent(origin -> {
            assertTrue(origin.startsWith("https://") || origin.startsWith("http://"), origin);
            assertFalse(origin.substring(origin.indexOf("://") + 3).contains("/"), origin);
        });
    }
}
