package net.vectolyn.cloud.launcher.platform;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects the {@link Platform} from what {@link SystemAccess} reports.
 *
 * <p>Container: {@code /.dockerenv} (Docker), {@code /run/.containerenv} (Podman), or a container
 * runtime in {@code /proc/1/cgroup} (cgroup v1). Under cgroup v2 with a private cgroup namespace
 * the cgroup file shows only {@code 0::/}, so the marker files are the main signal.
 *
 * <p>Pterodactyl: Wings sets {@code TZ}, {@code STARTUP}, {@code SERVER_MEMORY},
 * {@code SERVER_IP} and {@code SERVER_PORT} in every server container
 * ({@code server/server.go}, {@code GetEnvironmentVariables}); the Panel adds
 * {@code P_SERVER_UUID}, {@code P_SERVER_LOCATION} and {@code P_SERVER_ALLOCATION_LIMIT}
 * ({@code EnvironmentService}, {@code config/pterodactyl.php}). {@code P_SERVER_UUID} alone is
 * enough; without it, {@code STARTUP}, {@code SERVER_MEMORY} and {@code SERVER_PORT} must all be
 * present, so a stray {@code SERVER_PORT} on a normal host does not count.
 */
public final class PlatformDetector {

    private PlatformDetector() {
    }

    /**
     * Detects the platform.
     *
     * @param system access to environment, files and properties
     * @return the detected platform
     */
    public static Platform detect(SystemAccess system) {
        OperatingSystem os = OperatingSystem.fromOsName(system.property("os.name").orElse(null));
        String architecture = normalizeArchitecture(system.property("os.arch").orElse(""));
        boolean docker = os != OperatingSystem.WINDOWS && isContainer(system);
        boolean pterodactyl = isPterodactyl(system);
        return new Platform(os, architecture, docker || pterodactyl, pterodactyl, system.interactiveTerminal());
    }

    /**
     * Normalizes {@code os.arch}: {@code amd64}/{@code x86_64} become {@code x86_64},
     * {@code aarch64}/{@code arm64} become {@code aarch64}.
     *
     * @param osArch value of {@code os.arch}
     * @return the normalized name, lower case
     */
    public static String normalizeArchitecture(String osArch) {
        String arch = osArch.toLowerCase(Locale.ROOT).trim();
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "" -> "unknown";
            default -> arch;
        };
    }

    private static boolean isContainer(SystemAccess system) {
        if (system.exists(Path.of("/.dockerenv")) || system.exists(Path.of("/run/.containerenv"))) {
            return true;
        }
        Optional<String> cgroup = system.readFile(Path.of("/proc/1/cgroup"));
        if (cgroup.isEmpty()) {
            return false;
        }
        String content = cgroup.get();
        return content.contains("/docker") || content.contains("/containerd")
                || content.contains("/kubepods") || content.contains("/libpod");
    }

    private static boolean isPterodactyl(SystemAccess system) {
        if (present(system, "P_SERVER_UUID")) {
            return true;
        }
        return present(system, "STARTUP") && present(system, "SERVER_MEMORY") && present(system, "SERVER_PORT");
    }

    private static boolean present(SystemAccess system, String name) {
        return system.env(name).filter(value -> !value.isBlank()).isPresent();
    }
}
