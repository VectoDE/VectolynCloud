package net.vectolyn.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.vectolyn.cloud.launcher.platform.OperatingSystem;
import net.vectolyn.cloud.launcher.platform.Platform;
import net.vectolyn.cloud.launcher.platform.PlatformDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformDetectorTest {

    @Test
    void plainLinuxHost() {
        Platform platform = PlatformDetector.detect(new FakeSystemAccess().terminal(true));
        assertEquals(new Platform(OperatingSystem.LINUX, "x86_64", false, false, true), platform);
    }

    @Test
    void dockerByMarkerFile() {
        Platform platform = PlatformDetector.detect(new FakeSystemAccess().exists("/.dockerenv"));
        assertTrue(platform.docker());
        assertFalse(platform.pterodactyl());
        assertFalse(platform.terminal());
    }

    @Test
    void podmanByMarkerFile() {
        assertTrue(PlatformDetector.detect(new FakeSystemAccess().exists("/run/.containerenv")).docker());
    }

    @Test
    void dockerByCgroupV1() {
        FakeSystemAccess system = new FakeSystemAccess()
                .file("/proc/1/cgroup", "12:memory:/docker/3f1c9a\n11:cpu:/docker/3f1c9a\n");
        assertTrue(PlatformDetector.detect(system).docker());
    }

    @Test
    void cgroupV2RootIsNotAContainer() {
        assertFalse(PlatformDetector.detect(new FakeSystemAccess().file("/proc/1/cgroup", "0::/\n")).docker());
    }

    @Test
    void windowsIgnoresUnixMarkers() {
        FakeSystemAccess system = new FakeSystemAccess().property("os.name", "Windows Server 2022").exists("/.dockerenv");
        Platform platform = PlatformDetector.detect(system);
        assertEquals(OperatingSystem.WINDOWS, platform.os());
        assertFalse(platform.docker());
    }

    @Test
    void pterodactylFromWingsVariables() {
        // What Wings and the Panel put into a server container.
        FakeSystemAccess system = new FakeSystemAccess()
                .env("TZ", "UTC")
                .env("STARTUP", "java -jar VectolynCloud.jar --non-interactive")
                .env("SERVER_MEMORY", "4096")
                .env("SERVER_IP", "0.0.0.0")
                .env("SERVER_PORT", "25565")
                .env("P_SERVER_UUID", "9b7d2c4e-1f0a-4d8e-9c3b-5a6f7e8d9c0b")
                .env("P_SERVER_LOCATION", "de1");
        Platform platform = PlatformDetector.detect(system);
        assertTrue(platform.pterodactyl());
        assertTrue(platform.docker(), "a Pterodactyl server is a container");
    }

    @Test
    void pterodactylWithoutPanelUuid() {
        FakeSystemAccess system = new FakeSystemAccess()
                .env("STARTUP", "java -jar VectolynCloud.jar")
                .env("SERVER_MEMORY", "2048")
                .env("SERVER_PORT", "25565");
        assertTrue(PlatformDetector.detect(system).pterodactyl());
    }

    @Test
    void lonelyServerPortIsNotPterodactyl() {
        FakeSystemAccess system = new FakeSystemAccess().env("SERVER_PORT", "25565").env("SERVER_MEMORY", "1024");
        assertFalse(PlatformDetector.detect(system).pterodactyl());
    }

    @Test
    void blankPanelUuidIsIgnored() {
        assertFalse(PlatformDetector.detect(new FakeSystemAccess().env("P_SERVER_UUID", " ")).pterodactyl());
    }

    @ParameterizedTest
    @CsvSource({"amd64,x86_64", "x86_64,x86_64", "AARCH64,aarch64", "arm64,aarch64", "riscv64,riscv64", "'',unknown"})
    void architectureIsNormalized(String raw, String expected) {
        assertEquals(expected, PlatformDetector.normalizeArchitecture(raw));
    }

    @ParameterizedTest
    @CsvSource({"Windows 11,WINDOWS", "Linux,LINUX", "Mac OS X,MACOS", "FreeBSD,OTHER"})
    void operatingSystemFromOsName(String osName, OperatingSystem expected) {
        assertEquals(expected, OperatingSystem.fromOsName(osName));
    }

    @Test
    void settingsForTheNode() {
        Platform platform = new Platform(OperatingSystem.MACOS, "aarch64", false, false, true);
        assertEquals("macos", platform.toSettings().get("platform.os"));
        assertEquals("true", platform.toSettings().get("platform.terminal"));
    }
}
