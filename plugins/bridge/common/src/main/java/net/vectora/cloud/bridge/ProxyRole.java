package net.vectora.cloud.bridge;

import java.util.Locale;
import net.vectora.protocol.ServiceEnvironment;

/**
 * Which of the two proxies a Velocity bridge runs in. The main proxy ({@code modern} forwarding)
 * only takes current backends, the legacy proxy ({@code bungeeguard}) only 1.8.8 backends; a
 * {@code RegisterServer} whose {@code legacy} flag does not fit is refused.
 */
public enum ProxyRole {
    /** The main proxy in front of the Paper and Fabric groups. */
    MAIN,
    /** The legacy proxy in front of the PandaSpigot 1.8.8 groups. */
    LEGACY;

    /** Environment variable with the platform of the service ({@link ServiceEnvironment#SERVICE_PLATFORM}). */
    public static final String ENV_PLATFORM = ServiceEnvironment.SERVICE_PLATFORM;

    /** Value of {@link #ENV_PLATFORM} for the main proxy. */
    public static final String MAIN_PLATFORM = "VELOCITY";

    /** Value of {@link #ENV_PLATFORM} for the legacy proxy. */
    public static final String LEGACY_PLATFORM = "VELOCITY_LEGACY";

    /** Whether a server with this {@code legacy} flag belongs behind this proxy. */
    public boolean accepts(boolean legacyServer) {
        return legacyServer == (this == LEGACY);
    }

    /**
     * The role for a value of {@link #ENV_PLATFORM}: {@link #LEGACY} for
     * {@value #LEGACY_PLATFORM}, {@link #MAIN} for anything else, including a missing value.
     * Falling back to the main proxy is the safe side: it never accepts 1.8.8 backends, whose
     * forwarding is weaker.
     */
    public static ProxyRole fromPlatform(String platform) {
        return platform != null && LEGACY_PLATFORM.equals(normalized(platform)) ? LEGACY : MAIN;
    }

    /**
     * Whether {@code platform} is one of the two values the node sets for a proxy:
     * {@value #MAIN_PLATFORM} or {@value #LEGACY_PLATFORM}. Anything else (missing, a typo, a
     * backend platform) still makes the main proxy, but the bridge warns about it.
     */
    public static boolean isProxyPlatform(String platform) {
        return platform != null && (MAIN_PLATFORM.equals(normalized(platform)) || LEGACY_PLATFORM.equals(normalized(platform)));
    }

    /**
     * The line a proxy bridge logs when it connects: which role it took and, for a value that is
     * no proxy platform, why it fell back to the main proxy. The raw value is not repeated.
     */
    public static String describe(String platform) {
        ProxyRole role = fromPlatform(platform);
        String what = role == LEGACY
                ? "legacy proxy: only 1.8.8 servers (PandaSpigot) are registered, forwarding bungeeguard"
                : "main proxy: only current servers (Paper, Fabric) are registered, forwarding modern";
        if (isProxyPlatform(platform)) {
            return "VectoraCloud bridge runs as the " + what;
        }
        return "VectoraCloud bridge runs as the " + what + "; " + ENV_PLATFORM
                + (platform == null || platform.isBlank() ? " is not set" : " has an unknown value")
                + ", expected " + MAIN_PLATFORM + " or " + LEGACY_PLATFORM;
    }

    private static String normalized(String platform) {
        return platform.strip().toUpperCase(Locale.ROOT);
    }
}
