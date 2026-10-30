package net.vectolyn.cloud.database.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectolyn.cloud.database.DatabaseException;
import net.vectolyn.cloud.database.DatabaseProcesses.Command;
import net.vectolyn.cloud.download.provider.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Config file, command lines, platform check and layout of the managed mode. */
class MariaDbFilesTest {

    @TempDir
    Path home;

    @Test
    void configBindsToLoopbackWithTheOldLimits() {
        String ini = MariaDbConfigFile.render(3307, Optional.empty());
        Map<String, String> mysqld = section(ini, "mysqld");
        assertEquals("3307", mysqld.get("port"));
        assertEquals("127.0.0.1", mysqld.get("bind-address"));
        assertEquals("150", mysqld.get("max_connections"));
        assertEquals("256M", mysqld.get("innodb_buffer_pool_size"));
        assertEquals("utf8mb4", mysqld.get("character-set-server"));
        assertTrue(mysqld.containsKey("skip-name-resolve"));
        assertFalse(mysqld.containsKey("socket"), "Windows opens no named pipe");
        assertEquals("127.0.0.1", section(ini, "client").get("host"));
    }

    @Test
    void configOnLinuxSetsTheSocket() {
        String ini = MariaDbConfigFile.render(3307, Optional.of(Path.of("/tmp/vectolyn-mariadb-3307.sock")));
        assertEquals(Path.of("/tmp/vectolyn-mariadb-3307.sock").toString(), section(ini, "mysqld").get("socket"));
        assertThrows(IllegalArgumentException.class,
                () -> MariaDbConfigFile.render(3307, Optional.of(Path.of("/tmp/a b.sock"))));
        assertThrows(IllegalArgumentException.class, () -> MariaDbConfigFile.render(0, Optional.empty()));
    }

    /**
     * Counter-check against the hand-written file in the Database template: the managed mode keeps
     * every setting of it, apart from the bind address, which it pins to 127.0.0.1.
     */
    @Test
    void configKeepsEverySettingOfTheTemplate() throws IOException {
        Path template = repository().resolve("templates/Database/vectolyn-mariadb.ini");
        Map<String, String> old = section(Files.readString(template), "mysqld");
        Map<String, String> generated = section(MariaDbConfigFile.render(3307, Optional.empty()), "mysqld");
        for (Map.Entry<String, String> e : old.entrySet()) {
            if (e.getKey().equals("port") || e.getKey().equals("bind-address")) {
                continue;
            }
            assertEquals(e.getValue(), generated.get(e.getKey()), e.getKey());
        }
        assertEquals("{{VECTOLYN_DB_PORT}}", old.get("port"));
    }

    /** Counter-check: the manual init.sql grants exactly what the cloud grants. */
    @Test
    void templateInitSqlGrantsTheSameRights() throws IOException {
        String sql = Files.readString(repository().resolve("templates/Database/init.sql"));
        String grant = sql.lines().filter(l -> l.startsWith("GRANT ")).findFirst().orElseThrow();
        assertEquals("GRANT " + String.join(", ", net.vectolyn.cloud.database.sql.BootstrapStatements.APPLICATION_PRIVILEGES)
                + " ON", grant.substring(0, grant.indexOf(" ON") + 3));
        assertFalse(sql.contains("ALL PRIVILEGES"));
    }

    @Test
    void windowsCommands() throws DatabaseException, IOException {
        Path base = home.resolve("mariadb-11.8.9-winx64");
        Files.createDirectories(base.resolve("bin"));
        Files.writeString(base.resolve("bin/mysql_install_db.exe"), "");
        MariaDbLayout layout = new MariaDbLayout(home);

        Command init = MariaDbCommands.initialize(ManagedPlatform.WINDOWS_X64, base, layout);
        assertEquals(List.of(base.resolve("bin/mysql_install_db.exe").toString(), "--datadir=" + layout.dataDir()),
                init.arguments(), "older archives only have mysql_install_db.exe");
        Files.writeString(base.resolve("bin/mariadb-install-db.exe"), "");
        assertEquals(base.resolve("bin/mariadb-install-db.exe").toString(),
                MariaDbCommands.initialize(ManagedPlatform.WINDOWS_X64, base, layout).arguments().get(0));

        Command server = MariaDbCommands.server(ManagedPlatform.WINDOWS_X64, base, layout, Optional.empty());
        assertEquals(List.of(base.resolve("bin/mariadbd.exe").toString(), "--defaults-file=" + layout.configFile(),
                "--basedir=" + base, "--datadir=" + layout.dataDir(), "--console"), server.arguments());
        assertEquals(layout.logFile(), server.logFile());
    }

    @Test
    void linuxCommands() throws DatabaseException, IOException {
        Path base = home.resolve("mariadb-11.8.9-linux-systemd-x86_64");
        Files.createDirectories(base.resolve("scripts"));
        Files.writeString(base.resolve("scripts/mariadb-install-db"), "");
        MariaDbLayout layout = new MariaDbLayout(home);
        Command init = MariaDbCommands.initialize(ManagedPlatform.LINUX_X64_GLIBC, base, layout);
        assertEquals(List.of(base.resolve("scripts/mariadb-install-db").toString(), "--no-defaults",
                "--basedir=" + base, "--datadir=" + layout.dataDir(), "--auth-root-authentication-method=normal",
                "--skip-name-resolve"), init.arguments());
        Command server = MariaDbCommands.server(ManagedPlatform.LINUX_X64_GLIBC, base, layout,
                Optional.of(layout.initFile()));
        assertEquals("--defaults-file=" + layout.configFile(), server.arguments().get(1), "must be the first option");
        assertTrue(server.arguments().contains("--init-file=" + layout.initFile()));
        assertFalse(server.arguments().contains("--console"));
    }

    @Test
    void missingInstallProgramIsReported() {
        DatabaseException e = assertThrows(DatabaseException.class,
                () -> MariaDbCommands.initialize(ManagedPlatform.LINUX_X64_GLIBC, home, new MariaDbLayout(home)));
        assertEquals(DatabaseException.Reason.INITIALIZE, e.reason());
    }

    @Test
    void platforms() throws DatabaseException {
        assertEquals(ManagedPlatform.WINDOWS_X64, ManagedPlatform.of(
                new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, TargetPlatform.Libc.NONE)));
        assertEquals(ManagedPlatform.LINUX_X64_GLIBC, ManagedPlatform.of(
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64, TargetPlatform.Libc.GLIBC)));
        for (TargetPlatform other : List.of(
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.X64, TargetPlatform.Libc.MUSL),
                new TargetPlatform(TargetPlatform.Os.LINUX, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.GLIBC),
                new TargetPlatform(TargetPlatform.Os.MAC, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.NONE),
                new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.AARCH64, TargetPlatform.Libc.NONE))) {
            DatabaseException e = assertThrows(DatabaseException.class, () -> ManagedPlatform.of(other));
            assertEquals(DatabaseException.Reason.MANAGED_PLATFORM, e.reason());
            assertTrue(e.getMessage().contains(other.toString()), e.getMessage());
            assertTrue(e.getMessage().contains("switch to another database mode"), e.getMessage());
        }
        assertEquals("mariadbd.exe", ManagedPlatform.WINDOWS_X64.executable("mariadbd"));
        assertEquals("mariadbd", ManagedPlatform.LINUX_X64_GLIBC.executable("mariadbd"));
    }

    @Test
    void layoutKeepsTheOldNames() throws IOException {
        MariaDbLayout layout = new MariaDbLayout(home);
        assertEquals(home.resolve("data"), layout.dataDir());
        assertEquals(home.resolve("vectolyn-mariadb.ini"), layout.configFile());
        assertFalse(layout.initialized());
        Files.createDirectories(layout.dataDir().resolve("mysql"));
        assertTrue(layout.initialized());
    }

    @Test
    void installedVersionFits() {
        assertTrue(DownloadingMariaDbBinaries.versionFits(null, "11.8.9"));
        assertTrue(DownloadingMariaDbBinaries.versionFits("11.8", "11.8.9"));
        assertTrue(DownloadingMariaDbBinaries.versionFits("11.8.9", "11.8.9"));
        assertFalse(DownloadingMariaDbBinaries.versionFits("11.8.10", "11.8.9"));
        assertFalse(DownloadingMariaDbBinaries.versionFits("11.4", "11.8.9"));
        assertFalse(DownloadingMariaDbBinaries.versionFits("11.8", "11.80.1"));
        assertFalse(DownloadingMariaDbBinaries.versionFits(null, ""));
    }

    static Map<String, String> section(String ini, String name) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean inside = false;
        for (String raw : ini.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                inside = line.equals("[" + name + "]");
                continue;
            }
            if (inside) {
                int eq = line.indexOf('=');
                values.put(eq < 0 ? line : line.substring(0, eq).strip(), eq < 0 ? "" : line.substring(eq + 1).strip());
            }
        }
        return values;
    }

    static Path repository() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isDirectory(dir.resolve("templates"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
