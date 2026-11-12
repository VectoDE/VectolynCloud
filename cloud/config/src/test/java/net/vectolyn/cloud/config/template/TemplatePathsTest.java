// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which paths the API may write ({@link TemplatePaths#isEditablePath}). */
class TemplatePathsTest {

    @Test
    void textConfigurationOutsideCodeFoldersIsEditable() {
        for (String path : new String[] {"server.properties", "default/server.properties", "bukkit.yml",
                "config/paper-global.yml", "config/paper-world-defaults.yaml", "velocity.toml", "ops.json",
                "notes.txt", "config/luckperms/luckperms.conf", "Server.PROPERTIES", "a/b.Yml", "x.JSON"}) {
            assertTrue(TemplatePaths.isEditablePath(path), path);
        }
    }

    @Test
    void codeScriptsArchivesAndUnknownEndingsAreNot() {
        for (String path : new String[] {"paper.jar", "x.JAR", "plugin.Jar", "a/Main.class", "pack.zip",
                "start.sh", "start.bat", "start.cmd", "run.ps1", "lib.so", "lib.dll", "lib.dylib",
                "vectolyn-mariadb.ini", "init.sql", "README.md", "icon.png", "config.yml.jar", "Makefile",
                ".yml", "server.properties.bak"}) {
            assertFalse(TemplatePaths.isEditablePath(path), path);
        }
    }

    @Test
    void nothingInsideCodeFoldersInAnyCase() {
        for (String path : new String[] {"plugins/LuckPerms/config.yml", "Plugins/x.yml", "PLUGINS/x.yml",
                "default/plugins/a.yml", "mods/x.toml", "Mods/x.json", "libraries/x.txt", "LIBRARIES/x.yml",
                "versions/1.21/x.json", "bundler/x.properties", "cache/mojang.json", "Cache/x.yml",
                "a/b/c/plugins/d/e.yml", "plugıns/x.yml", "pluginſ/x.yml", "LIBRAR~1/x.yml",
                "PLUGIN~2/a.yml"}) {
            assertFalse(TemplatePaths.isEditablePath(path), path);
        }
        assertTrue(TemplatePaths.isEditablePath("plugins.yml"), "only folders count");
        assertTrue(TemplatePaths.isEditablePath("config/my-plugins/x.yml"), "only the exact folder name");
    }

    @Test
    void unsafeOrSecretPathsAreNeverEditable() {
        for (String path : new String[] {"../x.yml", "a/../x.yml", "a\\x.yml", "..\\x.yml",
                "/etc/x.yml", "C:/x.yml", "./x.yml", "", "a//x.yml", "secrets/x.yml", ".git/config.yml",
                "vectolyn.env", "forwarding.secret", "key.pem", "x.key.yml", "NUL.yml", "con.txt", "x.yml "}) {
            assertFalse(TemplatePaths.isEditablePath(path), path);
        }
    }
}
