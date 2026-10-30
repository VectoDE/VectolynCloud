package net.vectolyn.cloud.config.model;

import java.util.Optional;

/**
 * The one rule for whether the services of a group may run. The node plans, starts and stops by
 * it, builds the proxy lists by it, and the API reports it as {@code runs}; keeping it here, next to
 * the configuration it reads, means both always agree.
 */
public final class GroupRules {

    /** Template folder of the legacy proxy (Velocity with {@code bungeeguard}, in front of the 1.8.8 groups). */
    public static final String LEGACY_PROXY_TEMPLATE = "Legacy-Proxy";

    private GroupRules() {
    }

    /**
     * The server kind whose template a group uses.
     *
     * @param group the group
     * @return the kind, or empty for a template that is none of the known folders
     */
    public static Optional<ServerKind> kindOf(GroupConfig group) {
        for (ServerKind kind : ServerKind.values()) {
            if (kind.templateFolder().equals(group.template())) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * The switch under {@code servers:} in {@code cloud.yml} that a group follows: the server kind
     * of its template, and {@link ServerKind#LEGACY_SERVER} for a PandaSpigot group on a template
     * of its own, because every PandaSpigot server is a 1.8.8 server behind the legacy proxy.
     *
     * @param group the group
     * @return the switch, or empty for a group of the operator's own that no switch covers
     */
    public static Optional<ServerKind> switchOf(GroupConfig group) {
        Optional<ServerKind> kind = kindOf(group);
        if (kind.isEmpty() && group.software() == ServerSoftware.PANDASPIGOT) {
            return Optional.of(ServerKind.LEGACY_SERVER);
        }
        return kind;
    }

    /**
     * Tells whether a group is wanted by its own switch and by the switch of its server kind:
     * <ul>
     *   <li>{@code wanted: false} in the group file switches the group off, whatever its kind;</li>
     *   <li>a group on one of the known templates, and every PandaSpigot group, also needs its
     *       switch under {@code servers:} (see {@link #switchOf}); without a {@code servers:}
     *       section every switch counts as on;</li>
     *   <li>the database template is never a service of the node (the database module runs it);</li>
     *   <li>any other group is the operator's own and counts as wanted.</li>
     * </ul>
     * This overload does not look at {@code legacy-proxy.enabled}; see
     * {@link #isWanted(GroupConfig, CloudConfig)}.
     *
     * @param group  the group
     * @param wanted the switches from {@code cloud.yml}, may be {@code null}
     * @return whether services of the group may be created
     */
    public static boolean isWanted(GroupConfig group, WantedServers wanted) {
        if (!group.wantedOrDefault()) {
            return false;
        }
        Optional<ServerKind> kind = switchOf(group);
        if (kind.isEmpty()) {
            return true;
        }
        return kind.get() != ServerKind.DATABASE && (wanted == null || wanted.isWanted(kind.get()));
    }

    /**
     * Like {@link #isWanted(GroupConfig, WantedServers)}, and the 1.8.8 part of the network (the
     * legacy proxy, every PandaSpigot group and every group on the {@code Legacy-Server} template)
     * only while {@code legacy-proxy.enabled} is set. This is the rule for planning, for starts,
     * for the proxy lists, for stopping what a reload switched off, and for {@code runs} in the API.
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return whether services of the group may run
     */
    public static boolean isWanted(GroupConfig group, CloudConfig cloud) {
        if (needsLegacyProxy(group) && (cloud.legacyProxy() == null || !cloud.legacyProxy().enabled())) {
            return false;
        }
        return isWanted(group, cloud.servers());
    }

    /**
     * Tells why a group is not wanted, for messages. Only meaningful if
     * {@link #isWanted(GroupConfig, CloudConfig)} is false.
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return the setting that switches it off, for example {@code servers.lobby}
     */
    public static String unwantedBecause(GroupConfig group, CloudConfig cloud) {
        if (needsLegacyProxy(group) && (cloud.legacyProxy() == null || !cloud.legacyProxy().enabled())) {
            return "cloud.yml legacy-proxy.enabled";
        }
        if (!group.wantedOrDefault()) {
            return "wanted: false in its group file";
        }
        return switchOf(group).map(kind -> kind == ServerKind.DATABASE
                ? "the database template runs no service"
                : "cloud.yml servers." + kind.key()).orElse("cloud.yml servers");
    }

    /**
     * Tells whether a group belongs to the 1.8.8 part of the network: the legacy proxy (template
     * {@value #LEGACY_PROXY_TEMPLATE}) or a PandaSpigot server.
     *
     * @param group the group
     * @return whether it runs behind or as the legacy proxy
     */
    public static boolean isLegacy(GroupConfig group) {
        return group.software() == ServerSoftware.PANDASPIGOT || LEGACY_PROXY_TEMPLATE.equals(group.template());
    }

    /** The legacy proxy and the 1.8.8 servers run only together with {@code legacy-proxy.enabled}. */
    private static boolean needsLegacyProxy(GroupConfig group) {
        return isLegacy(group) || kindOf(group).orElse(null) == ServerKind.LEGACY_SERVER;
    }
}
