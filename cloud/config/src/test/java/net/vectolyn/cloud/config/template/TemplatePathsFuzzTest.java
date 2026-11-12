// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.file.Path;

/**
 * Fuzz target for {@link TemplatePaths}: a path the rules accept never leaves the template folder,
 * on any system, and the checks never throw. The path comes from the network (the API and the
 * cluster mirror). In a normal test run it executes in regression mode; the nightly workflow fuzzes
 * it on Linux.
 */
class TemplatePathsFuzzTest {

    private static final Path ROOT = Path.of("templates-root", "Lobby").toAbsolutePath().normalize();

    @FuzzTest
    void anAcceptedPathStaysInsideTheTemplate(@NotNull String path) {
        boolean safe = TemplatePaths.isSafePath(path);
        TemplatePaths.looksSecret(path.isEmpty() ? "x" : path);
        TemplatePaths.isTemplateName(path);
        if (TemplatePaths.isEditablePath(path)) {
            assertTrue(safe, "an editable path is always a safe path: " + path);
        }
        if (!safe) {
            return;
        }
        assertFalse(path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("//"), path);
        for (String segment : path.split("/", -1)) {
            assertFalse(segment.equals("..") || segment.equals(".") || segment.isEmpty(), path);
            assertFalse(segment.endsWith(".") || segment.endsWith(" "), path);
        }
        Path target = ROOT;
        for (String segment : path.split("/", -1)) {
            target = target.resolve(segment);
        }
        assertTrue(target.normalize().startsWith(ROOT) && !target.normalize().equals(ROOT), path);
    }
}
