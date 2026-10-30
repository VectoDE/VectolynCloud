package net.vectolyn.cloud.download.jre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.stream.Stream;
import net.vectolyn.cloud.download.archive.ArchiveException;
import net.vectolyn.cloud.download.archive.ExtractionLimits;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.provider.TargetPlatform;
import net.vectolyn.cloud.download.provider.TargetPlatform.Arch;
import net.vectolyn.cloud.download.provider.TargetPlatform.Libc;
import net.vectolyn.cloud.download.provider.TargetPlatform.Os;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class JreInstallerTest {

    private static final TargetPlatform LINUX_X64 = new TargetPlatform(Os.LINUX, Arch.X64, Libc.GLIBC);
    private static final String LINUX_NAME = "OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz";

    @TempDir
    Path home;

    private JreInstaller installer(TargetPlatform platform) {
        return new JreInstaller(home, ExtractionLimits.defaults(), platform);
    }

    private Path archive(String name, byte[] content) throws Exception {
        Path dir = Files.createDirectories(home.resolve("storage/artifacts"));
        return Files.write(dir.resolve(name), content);
    }

    private List<String> jreEntries() throws Exception {
        try (Stream<Path> entries = Files.list(home.resolve("storage/jre"))) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void installNames() {
        assertEquals("temurin-21-linux-x64", JreInstaller.installName("temurin", 21, LINUX_X64));
        assertEquals("temurin-25-linux-aarch64-musl",
                JreInstaller.installName("temurin", 25, new TargetPlatform(Os.LINUX, Arch.AARCH64, Libc.MUSL)));
        assertEquals("temurin-25-windows-x64",
                JreInstaller.installName("temurin", 25, new TargetPlatform(Os.WINDOWS, Arch.X64, Libc.NONE)));
        assertEquals("temurin-25-mac-aarch64",
                JreInstaller.installName("temurin", 25, new TargetPlatform(Os.MAC, Arch.AARCH64, Libc.NONE)));
        assertThrows(IllegalArgumentException.class, () -> installer(LINUX_X64).installDirectory("../x"));
    }

    @Test
    void installsALinuxJreAndFindsItAgain() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        Checksum sha = JreArchives.sha256(tar);
        JreInstaller installer = installer(LINUX_X64);
        JavaRuntime runtime = installer.install("temurin-21-linux-x64", archive("a", tar), LINUX_NAME, sha, 21);

        Path target = home.resolve("storage/jre/temurin-21-linux-x64");
        assertEquals(target.resolve("jdk-21.0.13+9-jre").toRealPath(), runtime.home().toRealPath());
        assertEquals(runtime.home().resolve("bin/java"), runtime.executable());
        assertEquals(21, runtime.feature());
        assertEquals("21.0.13", runtime.version());
        assertEquals(JavaRuntime.Origin.MANAGED, runtime.origin());
        assertTrue(Files.isRegularFile(target.resolve(JreInstaller.MARKER)));
        assertEquals(List.of("temurin-21-linux-x64"), jreEntries(), "no staging directory left");

        assertEquals(runtime, installer.installed("temurin-21-linux-x64", sha).orElseThrow());
        assertEquals(runtime, installer.installed("temurin-21-linux-x64", null).orElseThrow());
        assertTrue(installer.installed("temurin-21-linux-x64", JreArchives.sha256(new byte[] {1})).isEmpty(),
                "an install from another archive does not count");
        assertTrue(installer.installed("temurin-25-linux-x64", null).isEmpty());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void keepsTheExecutableBitOnUnix() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        JavaRuntime runtime = installer(LINUX_X64).install("temurin-21-linux-x64", archive("a", tar), LINUX_NAME,
                JreArchives.sha256(tar), 21);
        assertTrue(Files.isExecutable(runtime.executable()));
        assertTrue(Files.getPosixFilePermissions(runtime.executable()).contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(Files.getPosixFilePermissions(runtime.home().resolve("release"))
                .contains(PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void refusesAJavaThatIsNotExecutable() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0644);
        JreException e = assertThrows(JreException.class, () -> installer(LINUX_X64).install("temurin-21-linux-x64",
                archive("a", tar), LINUX_NAME, JreArchives.sha256(tar), 21));
        assertTrue(e.getMessage().contains("not executable"), e.getMessage());
        assertEquals(List.of(), jreEntries());
    }

    @Test
    void installsTheMacLayout() throws Exception {
        byte[] tar = JreArchives.macTarGz("25.0.5");
        TargetPlatform mac = new TargetPlatform(Os.MAC, Arch.AARCH64, Libc.NONE);
        JavaRuntime runtime = installer(mac).install("temurin-25-mac-aarch64", archive("m", tar),
                "OpenJDK25U-jre_aarch64_mac_hotspot_25.0.5_8.tar.gz", JreArchives.sha256(tar), 25);
        assertTrue(runtime.home().endsWith(Path.of("Contents", "Home")), runtime.home().toString());
        assertTrue(Files.isRegularFile(runtime.executable()));
    }

    @Test
    void installsTheWindowsZip() throws Exception {
        byte[] zip = JreArchives.windowsZip("25.0.5");
        TargetPlatform windows = new TargetPlatform(Os.WINDOWS, Arch.X64, Libc.NONE);
        JavaRuntime runtime = installer(windows).install("temurin-25-windows-x64", archive("w", zip),
                "OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip", JreArchives.sha256(zip), 25);
        assertEquals("java.exe", runtime.executable().getFileName().toString());
        assertTrue(Files.isRegularFile(runtime.executable()));
    }

    @Test
    void refusesAnArchiveWithAnotherJavaVersion() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("25.0.5", 0755);
        JreException e = assertThrows(JreException.class, () -> installer(LINUX_X64).install("temurin-21-linux-x64",
                archive("a", tar), LINUX_NAME, JreArchives.sha256(tar), 21));
        assertTrue(e.getMessage().contains("contains Java 25.0.5, expected Java 21"), e.getMessage());
        assertEquals(List.of(), jreEntries(), "nothing installed, no staging directory left");
    }

    @Test
    void refusesAnArchiveWithoutJavaHome() throws Exception {
        byte[] zip = JreArchives.zip(List.of("readme.txt"), List.of("not a JRE"));
        assertThrows(JreException.class, () -> installer(LINUX_X64).install("temurin-21-linux-x64", archive("z", zip),
                "OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.zip", JreArchives.sha256(zip), 21));
        assertEquals(List.of(), jreEntries());
    }

    @Test
    void zipSlipInAJreArchiveLeavesNothingBehind() throws Exception {
        byte[] zip = JreArchives.zip(List.of("jdk/release", "../../evil.txt"), List.of(JreArchives.release("21.0.13"), "x"));
        assertThrows(ArchiveException.class, () -> installer(LINUX_X64).install("temurin-21-linux-x64",
                archive("z", zip), "x.zip", JreArchives.sha256(zip), 21));
        assertFalse(Files.exists(home.resolve("evil.txt")));
        assertFalse(Files.exists(home.getParent().resolve("evil.txt")));
        assertEquals(List.of(), jreEntries());
    }

    @Test
    void anUpdateReplacesTheOldInstall() throws Exception {
        JreInstaller installer = installer(LINUX_X64);
        byte[] first = JreArchives.linuxTarGz("21.0.12", 0755);
        installer.install("temurin-21-linux-x64", archive("1", first), LINUX_NAME, JreArchives.sha256(first), 21);
        byte[] second = JreArchives.linuxTarGz("21.0.13", 0755);
        JavaRuntime runtime = installer.install("temurin-21-linux-x64", archive("2", second), LINUX_NAME,
                JreArchives.sha256(second), 21);
        assertEquals("21.0.13", runtime.version());
        assertEquals(List.of("temurin-21-linux-x64"), jreEntries(), "the old install is gone");
        try (Stream<Path> files = Files.list(home.resolve("storage/jre/temurin-21-linux-x64"))) {
            assertEquals(2, files.count(), "only the new Java home and the marker");
        }
    }

    @Test
    void aTamperedMarkerIsNotTrusted() throws Exception {
        byte[] tar = JreArchives.linuxTarGz("21.0.13", 0755);
        JreInstaller installer = installer(LINUX_X64);
        installer.install("temurin-21-linux-x64", archive("a", tar), LINUX_NAME, JreArchives.sha256(tar), 21);
        Path marker = home.resolve("storage/jre/temurin-21-linux-x64").resolve(JreInstaller.MARKER);
        String original = Files.readString(marker, StandardCharsets.ISO_8859_1);
        for (String value : new String[] {"../../..", "/usr/lib/jvm/x", "jdk/../../x", "", "C\\:/x"}) {
            Files.writeString(marker, original.replaceAll("(?m)^home=.*$", "home=" + value.replace("\\", "\\\\")),
                    StandardCharsets.ISO_8859_1);
            assertTrue(installer.installed("temurin-21-linux-x64", null).isEmpty(), value);
        }
        Files.writeString(marker, "garbage \\uZZZZ", StandardCharsets.ISO_8859_1);
        assertTrue(installer.installed("temurin-21-linux-x64", null).isEmpty());
    }

    @Test
    void removesLeftoversOfAnInterruptedInstall() throws Exception {
        JreInstaller installer = installer(LINUX_X64);
        Files.createDirectories(home.resolve("storage/jre/.staging-temurin-21-linux-x64-123/jdk/bin"));
        Files.writeString(home.resolve("storage/jre/.staging-temurin-21-linux-x64-123/jdk/bin/java"), "x");
        Files.createDirectories(home.resolve("storage/jre/.old-temurin-21-linux-x64-abc"));
        Files.createDirectories(home.resolve("storage/jre/temurin-25-linux-x64"));
        assertEquals(2, installer.removeLeftovers());
        assertEquals(List.of("temurin-25-linux-x64"), jreEntries());
    }
}
