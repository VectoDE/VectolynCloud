package net.vectora.cloud.launcher.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Where VectoraCloud runs. Detected once at startup by {@link PlatformDetector} and later used by
 * the setup assistant, for example to skip the Docker database mode inside Pterodactyl or to ask
 * no questions without a terminal.
 *
 * @param os           operating system family
 * @param architecture normalized CPU architecture: {@code x86_64}, {@code aarch64}, or the raw
 *                     {@code os.arch} value for anything else
 * @param docker       running inside a container (Docker, Podman, containerd); Pterodactyl
 *                     servers are containers too
 * @param pterodactyl  running as a Pterodactyl (or Pelican) server
 * @param terminal     standard input and output are an interactive terminal
 */
public record Platform(
        OperatingSystem os,
        String architecture,
        boolean docker,
        boolean pterodactyl,
        boolean terminal) {

    /** Validates that no component is {@code null}. */
    public Platform {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(architecture, "architecture");
    }

    /**
     * Flattens the platform into string settings for the node, which cannot see launcher types
     * at compile time (see {@code NodeStarter}).
     *
     * @return keys {@code platform.os}, {@code platform.arch}, {@code platform.docker},
     *         {@code platform.pterodactyl}, {@code platform.terminal}
     */
    public Map<String, String> toSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("platform.os", os.name().toLowerCase(java.util.Locale.ROOT));
        settings.put("platform.arch", architecture);
        settings.put("platform.docker", Boolean.toString(docker));
        settings.put("platform.pterodactyl", Boolean.toString(pterodactyl));
        settings.put("platform.terminal", Boolean.toString(terminal));
        return settings;
    }
}
