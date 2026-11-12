// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.setup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.validation.ConfigNames;

/**
 * The Minecraft versions the assistant offers for the groups it creates (every default group except
 * the 1.8.8 server, which always runs {@link NetworkServer#LEGACY_MINECRAFT_VERSION}).
 *
 * <p>The rule: a version is offered if the bridges of this release support it, that is, it lies
 * between {@link #OLDEST} and {@link NetworkServer#MINECRAFT_VERSION} (the Paper API the Paper
 * bridge is built against), patch releases of both included. Of those, the assistant offers the
 * ones with a stable Paper build (the node asks PaperMC's Fill API before the setup runs), newest
 * first, and suggests the newest. Without an answer from Fill (offline, blocked) it offers
 * {@link NetworkServer#MINECRAFT_VERSION} alone.
 */
public final class MinecraftVersions {

    /**
     * Oldest Minecraft version the bridges support: the first release with the year numbering, which
     * needs Java 25 like the bridges.
     */
    public static final String OLDEST = "26.1";

    private static final Pattern DOT = Pattern.compile("\\.");

    private MinecraftVersions() {
    }

    /** Whether the bridges of this release support {@code version}. */
    public static boolean supported(String version) {
        if (version == null || !version.matches(ConfigNames.MINECRAFT_VERSION)) {
            return false;
        }
        return compareRelease(version, OLDEST) >= 0 && compareRelease(version, NetworkServer.MINECRAFT_VERSION) <= 0;
    }

    /**
     * The versions to offer: the supported ones of {@code stable}, without repeats, newest first; the
     * compiled-in {@link NetworkServer#MINECRAFT_VERSION} alone if none is left.
     *
     * @param stable versions with a stable Paper build, in any order; may hold unsupported ones
     * @return never empty
     */
    public static List<String> offered(List<String> stable) {
        Objects.requireNonNull(stable, "stable");
        List<String> result = new ArrayList<>();
        for (String version : stable) {
            if (supported(version) && !result.contains(version)) {
                result.add(version);
            }
        }
        if (result.isEmpty()) {
            return List.of(NetworkServer.MINECRAFT_VERSION);
        }
        result.sort(Comparator.comparing((String v) -> v, MinecraftVersions::compare).reversed());
        return List.copyOf(result);
    }

    /** The newest offered version, the assistant's default. */
    public static String newest(List<String> stable) {
        return offered(stable).getFirst();
    }

    /** Compares two versions number by number; {@code 26.3} is older than {@code 26.3.1}. */
    static int compare(String a, String b) {
        String[] x = parts(a);
        String[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? Integer.parseInt(x[i]) : 0;
            int right = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static String[] parts(String version) {
        return DOT.splitAsStream(version).toArray(String[]::new);
    }

    /** Compares only the release ({@code major.minor}), so a patch of a supported release counts as supported. */
    private static int compareRelease(String a, String b) {
        String[] x = parts(a);
        String[] y = parts(b);
        int major = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
        return major != 0 ? major : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
    }
}
