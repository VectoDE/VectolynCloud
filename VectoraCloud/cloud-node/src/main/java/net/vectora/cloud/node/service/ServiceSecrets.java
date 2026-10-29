package net.vectora.cloud.node.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.template.ServerPlatform;

/**
 * Which secret of {@code secrets/vectora.env} a service may see, by its {@link ServerPlatform}.
 * Need to know: a template only gets the secrets its server actually uses, as placeholders.
 *
 * <table>
 *   <caption>Secrets per platform</caption>
 *   <tr><th>Platform</th><th>Placeholders</th></tr>
 *   <tr><td>{@code VELOCITY} (main proxy)</td><td>{@value #FORWARDING} ({@code forwarding.secret}),
 *       {@value #FLOODGATE} ({@code key.pem}), {@value #DB_PASSWORD} (LuckPerms)</td></tr>
 *   <tr><td>{@code VELOCITY_LEGACY}</td><td>{@value #LEGACY_FORWARDING} ({@code forwarding.secret})</td></tr>
 *   <tr><td>{@code PAPER}, {@code FABRIC}</td><td>{@value #FORWARDING}, {@value #MANAGEMENT},
 *       {@value #DB_PASSWORD}, {@value #FLOODGATE}</td></tr>
 *   <tr><td>{@code PANDASPIGOT}</td><td>none; the legacy bridge gets {@value #LEGACY_FORWARDING} in its
 *       environment instead (see {@link #legacyForwardingFor})</td></tr>
 *   <tr><td>{@code NONE} (database template)</td><td>{@value #DB_PASSWORD}</td></tr>
 * </table>
 *
 * {@value #DB_ROOT_PASSWORD} is in no row: no template ever sees it. Other {@code VECTORA_*} keys an
 * operator adds to {@code vectora.env} are no placeholders either.
 */
public final class ServiceSecrets {

    /** Velocity modern-forwarding secret. */
    public static final String FORWARDING = "VECTORA_FORWARDING_SECRET";
    /** Forwarding secret of the legacy proxy ({@code bungeeguard} token). */
    public static final String LEGACY_FORWARDING = "VECTORA_LEGACY_FORWARDING_SECRET";
    /** Password of the LuckPerms database user. */
    public static final String DB_PASSWORD = "VECTORA_DB_PASSWORD";
    /** Password of the MariaDB root user; only the database module uses it. */
    public static final String DB_ROOT_PASSWORD = "VECTORA_DB_ROOT_PASSWORD";
    /** {@code management-server-secret} of {@code server.properties}. */
    public static final String MANAGEMENT = "VECTORA_MANAGEMENT_SECRET";
    /** Floodgate AES key, base64. */
    public static final String FLOODGATE = "VECTORA_FLOODGATE_KEY_B64";

    private static final Map<ServerPlatform, List<String>> PLACEHOLDERS = placeholders();

    private ServiceSecrets() {
    }

    private static Map<ServerPlatform, List<String>> placeholders() {
        Map<ServerPlatform, List<String>> map = new EnumMap<>(ServerPlatform.class);
        map.put(ServerPlatform.VELOCITY, List.of(FORWARDING, FLOODGATE, DB_PASSWORD));
        map.put(ServerPlatform.VELOCITY_LEGACY, List.of(LEGACY_FORWARDING));
        map.put(ServerPlatform.PAPER, List.of(FORWARDING, MANAGEMENT, DB_PASSWORD, FLOODGATE));
        map.put(ServerPlatform.FABRIC, List.of(FORWARDING, MANAGEMENT, DB_PASSWORD, FLOODGATE));
        map.put(ServerPlatform.PANDASPIGOT, List.of());
        map.put(ServerPlatform.NONE, List.of(DB_PASSWORD));
        return Collections.unmodifiableMap(map);
    }

    /**
     * The secrets a platform's templates may use, in a fixed order.
     *
     * @param platform the platform
     * @return the placeholder names
     */
    public static List<String> placeholdersOf(ServerPlatform platform) {
        return PLACEHOLDERS.get(platform);
    }

    /**
     * The secret placeholders for one service: exactly the ones of its platform, all of them.
     *
     * @param platform the platform of the service
     * @param secrets  {@code secrets/vectora.env}
     * @return placeholder name to value
     * @throws ServiceException if a secret the platform needs is missing or empty in
     *                          {@code vectora.env}, or the legacy secret equals the modern one
     */
    public static Map<String, String> placeholders(ServerPlatform platform, EnvFile secrets) throws ServiceException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : placeholdersOf(platform)) {
            values.put(key, require(secrets, key, platform).reveal());
        }
        if (platform == ServerPlatform.VELOCITY_LEGACY) {
            checkLegacyDiffers(secrets);
        }
        return values;
    }

    /**
     * The forwarding secret of the legacy proxy for the environment of a service, which only a
     * PandaSpigot server gets: its legacy bridge checks the {@code bungeeguard-token} against it.
     *
     * @param platform the platform of the service
     * @param secrets  {@code secrets/vectora.env}
     * @return the secret for {@code PANDASPIGOT}, empty for every other platform
     * @throws ServiceException if a PandaSpigot server would need it and it is missing, empty or
     *                          equal to the modern secret
     */
    public static Optional<Secret> legacyForwardingFor(ServerPlatform platform, EnvFile secrets) throws ServiceException {
        if (platform != ServerPlatform.PANDASPIGOT) {
            return Optional.empty();
        }
        Secret secret = require(secrets, LEGACY_FORWARDING, platform);
        checkLegacyDiffers(secrets);
        return Optional.of(secret);
    }

    /** Every secret name any platform may see; {@value #DB_ROOT_PASSWORD} is never among them. */
    static Set<String> allPlaceholders() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        PLACEHOLDERS.values().forEach(all::addAll);
        return Collections.unmodifiableSet(all);
    }

    private static Secret require(EnvFile secrets, String key, ServerPlatform platform) throws ServiceException {
        Optional<Secret> value = secrets.get(key).filter(secret -> !secret.reveal().isEmpty());
        if (value.isEmpty()) {
            throw new ServiceException("secrets/vectora.env has no value for " + key + ", which every "
                    + platform + " service needs");
        }
        return value.get();
    }

    /**
     * In {@code bungeeguard} mode the legacy proxy sends its secret in plain text to the 1.8.8
     * backends; if it were the modern one, every 1.8.8 server would hold the main proxy's secret.
     */
    private static void checkLegacyDiffers(EnvFile secrets) throws ServiceException {
        Optional<Secret> legacy = secrets.get(LEGACY_FORWARDING);
        Optional<Secret> modern = secrets.get(FORWARDING);
        if (legacy.isPresent() && modern.isPresent() && legacy.get().equals(modern.get())) {
            throw new ServiceException("secrets/vectora.env: " + LEGACY_FORWARDING + " equals " + FORWARDING
                    + "; the legacy proxy needs a secret of its own");
        }
    }
}
