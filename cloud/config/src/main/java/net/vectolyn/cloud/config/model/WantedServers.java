package net.vectolyn.cloud.config.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The "wanted" switch of each server kind. Only wanted servers are rendered from their template,
 * started, and referenced by the proxy, the lobby navigator and the portals. A server missing
 * from {@code cloud.yml} counts as not wanted.
 *
 * @param proxy    Velocity proxy
 * @param lobby    lobby
 * @param survival Survival
 * @param creative Creative
 * @param mod      Mod-Server (Fabric)
 * @param end      End-Server
 * @param database MariaDB
 * @param minigame minigame groups
 * @param legacyServer 1.8.8 servers behind the legacy proxy ({@code legacy-server}); they also need
 *                     {@code legacy-proxy.enabled}
 */
public record WantedServers(boolean proxy, boolean lobby, boolean survival, boolean creative,
                            boolean mod, boolean end, boolean database, boolean minigame, boolean legacyServer) {

    /** Every server wanted. */
    public static WantedServers all() {
        return new WantedServers(true, true, true, true, true, true, true, true, true);
    }

    /** No server wanted. */
    public static WantedServers none() {
        return new WantedServers(false, false, false, false, false, false, false, false, false);
    }

    /** Whether the given server is wanted. */
    public boolean isWanted(ServerKind kind) {
        return switch (kind) {
            case PROXY -> proxy;
            case LOBBY -> lobby;
            case SURVIVAL -> survival;
            case CREATIVE -> creative;
            case MOD -> mod;
            case END -> end;
            case DATABASE -> database;
            case MINIGAME -> minigame;
            case LEGACY_SERVER -> legacyServer;
        };
    }

    /** A copy with the switch of {@code kind} set to {@code wanted}. */
    public WantedServers with(ServerKind kind, boolean wanted) {
        return new WantedServers(
                kind == ServerKind.PROXY ? wanted : proxy,
                kind == ServerKind.LOBBY ? wanted : lobby,
                kind == ServerKind.SURVIVAL ? wanted : survival,
                kind == ServerKind.CREATIVE ? wanted : creative,
                kind == ServerKind.MOD ? wanted : mod,
                kind == ServerKind.END ? wanted : end,
                kind == ServerKind.DATABASE ? wanted : database,
                kind == ServerKind.MINIGAME ? wanted : minigame,
                kind == ServerKind.LEGACY_SERVER ? wanted : legacyServer);
    }

    /** All switches, in declaration order of {@link ServerKind}. */
    public Map<ServerKind, Boolean> asMap() {
        Map<ServerKind, Boolean> map = new EnumMap<>(ServerKind.class);
        for (ServerKind kind : ServerKind.values()) {
            map.put(kind, isWanted(kind));
        }
        return Collections.unmodifiableMap(map);
    }
}
