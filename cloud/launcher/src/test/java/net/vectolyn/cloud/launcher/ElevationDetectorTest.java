package net.vectolyn.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.vectolyn.cloud.launcher.platform.OperatingSystem;
import net.vectolyn.cloud.launcher.security.Elevation;
import net.vectolyn.cloud.launcher.security.ElevationDetector;
import org.junit.jupiter.api.Test;

class ElevationDetectorTest {

    private static final String WHOAMI = "C:\\Windows\\System32\\whoami.exe";

    // Output of "whoami /groups /fo csv /nh" on a German Windows 11, shortened.
    private static final String WHOAMI_HIGH = """
            "Jeder","Bekannte Gruppe","S-1-1-0","Verbindliche Gruppe, Standardmäßig aktiviert, Aktivierte Gruppe"
            "VORDEFINIERT\\Administratoren","Alias","S-1-5-32-544","Verbindliche Gruppe, Standardmäßig aktiviert, Aktivierte Gruppe, Gruppenbesitzer"
            "Verbindliche Beschriftung\\Hohe Verbindlichkeitsstufe","Bezeichnung","S-1-16-12288",""
            """;
    // Same account in a normal console: Administrators is deny-only, integrity Medium.
    private static final String WHOAMI_MEDIUM = """
            "Everyone","Well-known group","S-1-1-0","Mandatory group, Enabled by default, Enabled group"
            "BUILTIN\\Administrators","Alias","S-1-5-32-544","Group used for deny only"
            "Mandatory Label\\Medium Mandatory Level","Label","S-1-16-8192",""
            """;

    @Test
    void unixRootViaUnixSystem() {
        Elevation result = ElevationDetector.detect(OperatingSystem.LINUX, FakeSystemAccess.linuxRoot());
        assertTrue(result.elevated());
        assertTrue(result.evidence().contains("uid=0"), result.evidence());
    }

    @Test
    void unixNormalUserViaUnixSystem() {
        Elevation result = ElevationDetector.detect(OperatingSystem.MACOS, FakeSystemAccess.linuxUser());
        assertEquals(Elevation.State.NOT_ELEVATED, result.state());
    }

    @Test
    void procStatusUsesEffectiveUid() {
        // Real 1000, effective 0 (setuid): counts as root.
        FakeSystemAccess system = new FakeSystemAccess()
                .file("/proc/self/status", "Name:\tjava\nUid:\t1000\t0\t0\t0\nGid:\t1000\t1000\t1000\t1000\n");
        Elevation result = ElevationDetector.detect(OperatingSystem.LINUX, system);
        assertTrue(result.elevated());
        assertTrue(result.evidence().startsWith("/proc/self/status"), result.evidence());
        assertTrue(system.executed().isEmpty(), "no command needed when /proc answers");
    }

    @Test
    void procStatusNormalUser() {
        FakeSystemAccess system = new FakeSystemAccess().file("/proc/self/status", "Uid:\t1000\t1000\t1000\t1000\n");
        assertEquals(Elevation.State.NOT_ELEVATED, ElevationDetector.detect(OperatingSystem.LINUX, system).state());
    }

    @Test
    void procStatusWithOversizedUidIsUnknownInsteadOfCrashing() {
        // 20 digits do not fit into a long.
        FakeSystemAccess system = new FakeSystemAccess()
                .file("/proc/self/status", "Uid:\t12345678901234567890\t12345678901234567890\t0\t0\n");
        assertEquals(Elevation.State.UNKNOWN, ElevationDetector.detect(OperatingSystem.LINUX, system).state());
    }

    @Test
    void procStatusWithOversizedEffectiveUidIsNotTruncated() {
        // The pattern must not take the first ten digits of a longer number.
        FakeSystemAccess system = new FakeSystemAccess()
                .file("/proc/self/status", "Uid:\t1000\t00000000001234567890\t0\t0\n");
        assertEquals(Elevation.State.UNKNOWN, ElevationDetector.detect(OperatingSystem.LINUX, system).state());
    }

    @Test
    void idCommandAsLastUnixFallback() {
        FakeSystemAccess root = new FakeSystemAccess().exists("/usr/bin/id").command("0\n", "/usr/bin/id", "-u");
        assertTrue(ElevationDetector.detect(OperatingSystem.MACOS, root).elevated());
        FakeSystemAccess user = new FakeSystemAccess().exists("/usr/bin/id").command("501\n", "/usr/bin/id", "-u");
        assertEquals(Elevation.State.NOT_ELEVATED, ElevationDetector.detect(OperatingSystem.MACOS, user).state());
    }

    @Test
    void idCommandFromBinWhenUsrBinIsMissing() {
        FakeSystemAccess system = new FakeSystemAccess().exists("/bin/id").command("0\n", "/bin/id", "-u");
        assertTrue(ElevationDetector.detect(OperatingSystem.LINUX, system).elevated());
        assertEquals(List.of(List.of("/bin/id", "-u")), system.executed());
    }

    @Test
    void idIsNeverLookedUpOnThePath() {
        FakeSystemAccess system = new FakeSystemAccess().command("1000\n", "id", "-u");
        assertEquals(Elevation.State.UNKNOWN, ElevationDetector.detect(OperatingSystem.LINUX, system).state());
        assertTrue(system.executed().isEmpty(), system.executed().toString());
    }

    @Test
    void unixWithoutAnySourceIsUnknown() {
        FakeSystemAccess system = new FakeSystemAccess().exists("/usr/bin/id").command("garbage", "/usr/bin/id", "-u");
        assertEquals(Elevation.State.UNKNOWN, ElevationDetector.detect(OperatingSystem.LINUX, system).state());
    }

    @Test
    void idWithOversizedUidIsUnknown() {
        FakeSystemAccess system = new FakeSystemAccess().exists("/usr/bin/id")
                .command("12345678901234567890\n", "/usr/bin/id", "-u");
        assertEquals(Elevation.State.UNKNOWN, ElevationDetector.detect(OperatingSystem.LINUX, system).state());
    }

    @Test
    void windowsHighIntegrityViaNtSystem() {
        FakeSystemAccess system = new FakeSystemAccess().windowsSids("S-1-16-12288", "S-1-1-0", "S-1-5-32-544");
        Elevation result = ElevationDetector.detect(OperatingSystem.WINDOWS, system);
        assertTrue(result.elevated());
        assertEquals("NTSystem S-1-16-12288", result.evidence());
    }

    @Test
    void windowsMediumIntegrityAdminIsNotElevated() {
        // Deny-only Administrators SID is present, but the integrity level decides.
        FakeSystemAccess system = new FakeSystemAccess().windowsSids("S-1-16-8192", "S-1-5-32-544");
        assertFalse(ElevationDetector.detect(OperatingSystem.WINDOWS, system).elevated());
    }

    @Test
    void windowsSystemIntegrity() {
        FakeSystemAccess system = new FakeSystemAccess().windowsSids("S-1-16-16384", "S-1-5-18");
        assertTrue(ElevationDetector.detect(OperatingSystem.WINDOWS, system).elevated());
    }

    @Test
    void windowsWhoamiHighFallback() {
        FakeSystemAccess system = new FakeSystemAccess()
                .env("SystemRoot", "C:\\Windows").command(WHOAMI_HIGH, WHOAMI, "/groups", "/fo", "csv", "/nh");
        Elevation result = ElevationDetector.detect(OperatingSystem.WINDOWS, system);
        assertTrue(result.elevated());
        assertEquals("whoami S-1-16-12288", result.evidence());
    }

    @Test
    void windowsWhoamiMediumFallback() {
        FakeSystemAccess system = new FakeSystemAccess()
                .env("SystemRoot", "C:\\Windows").command(WHOAMI_MEDIUM, WHOAMI, "/groups", "/fo", "csv", "/nh");
        assertEquals(Elevation.State.NOT_ELEVATED, ElevationDetector.detect(OperatingSystem.WINDOWS, system).state());
    }

    @Test
    void windowsWhoamiUsedWhenNtSystemHasNoLabel() {
        FakeSystemAccess system = new FakeSystemAccess()
                .windowsSids("S-1-1-0")
                .env("SystemRoot", "C:\\Windows").command(WHOAMI_HIGH, WHOAMI, "/groups", "/fo", "csv", "/nh");
        assertTrue(ElevationDetector.detect(OperatingSystem.WINDOWS, system).elevated());
        assertEquals(List.of(List.of(WHOAMI, "/groups", "/fo", "csv", "/nh")), system.executed());
    }

    @Test
    void windowsWhoamiPathFollowsSystemRoot() {
        FakeSystemAccess system = new FakeSystemAccess()
                .env("SystemRoot", "D:\\WIN\\")
                .command(WHOAMI_HIGH, "D:\\WIN\\System32\\whoami.exe", "/groups", "/fo", "csv", "/nh");
        assertTrue(ElevationDetector.detect(OperatingSystem.WINDOWS, system).elevated());
    }

    @Test
    void windowsWithoutSystemRootRunsNoCommand() {
        FakeSystemAccess system = new FakeSystemAccess().command(WHOAMI_HIGH, "whoami", "/groups", "/fo", "csv", "/nh");
        assertEquals(Elevation.State.UNKNOWN, ElevationDetector.detect(OperatingSystem.WINDOWS, system).state());
        assertTrue(system.executed().isEmpty(), system.executed().toString());
    }

    @Test
    void windowsWithoutAnySourceIsUnknown() {
        assertEquals(Elevation.State.UNKNOWN,
                ElevationDetector.detect(OperatingSystem.WINDOWS, new FakeSystemAccess()).state());
    }
}
