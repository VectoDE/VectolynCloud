package net.vectora.cloud.backup.archive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackupFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"paper.jar", "plugins/LuckPerms.JAR", "mods/fabric-api.jar", "logs/latest.log",
        "world/session.lock", "plugins/LuckPerms/luckperms-h2-v2.lock.db", "x.trace.db", "forwarding.secret",
        "plugins/floodgate/key.pem", "keystore.p12", "a.jks", "server.key", ".env", "vectora.env", "upload.tmp",
        "logs/2026-10-29-1.log.gz", "server.properties", "SERVER.PROPERTIES", "config/paper-global.yml",
        "config/FabricProxy-Lite.toml", "plugins/LuckPerms/config.yml", "plugins/luckperms/config.yml",
        "config/luckperms/luckperms.conf"})
    void excludedFiles(String path) {
        assertTrue(BackupFilter.excludesFile(Path.of(path)), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"world/server.properties", "world/level.dat", "config/paper-world-defaults.yml",
        "plugins/x/config/paper-global.yml", "config/floodgate/config.yml", "world/region/r.0.0.mca",
        "plugins/TAB/config.yml", "plugins/LuckPerms/luckperms-h2-v2.mv.db", "whitelist.json", "ops.json", "jarvis.txt", "catalog.yml"})
    void keptFiles(String path) {
        assertFalse(BackupFilter.excludesFile(Path.of(path)), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"logs", "cache", "Cache", "libraries", "versions", "crash-reports", "debug", "bundler",
        ".paper-remapped", "plugins/x/secrets", ".fabric", "config/.mixin.out"})
    void excludedFolders(String path) {
        assertTrue(BackupFilter.excludesFolder(Path.of(path)), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"world", "plugins", "config", "world_nether", "plugins/LuckPerms", "mods"})
    void keptFolders(String path) {
        assertFalse(BackupFilter.excludesFolder(Path.of(path)), path);
    }
}
