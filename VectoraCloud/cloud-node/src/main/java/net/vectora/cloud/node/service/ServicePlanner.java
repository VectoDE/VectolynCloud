package net.vectora.cloud.node.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.WantedServers;

/**
 * Pure decisions of the service core, without any I/O: which groups are wanted, which services a
 * configuration asks for, where each service works, and how long to wait before a restart.
 */
public final class ServicePlanner {

    /** Folder below the home where dynamic services work. */
    public static final String DYNAMIC_DIR = "temp/services";

    /** Template folder of the legacy proxy (Velocity with {@code bungeeguard}, in front of the 1.8.8 groups). */
    public static final String LEGACY_PROXY_TEMPLATE = "Legacy-Proxy";

    private ServicePlanner() {
    }

    /**
     * The server kind whose template a group uses.
     *
     * @param group the group
     * @return the kind, or empty for a template that is none of the nine known folders
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
     *   <li>{@code wanted: false} in the group file switches the group off, whatever its kind
     *       (for example one minigame out of several);</li>
     *   <li>a group on one of the known templates, and every PandaSpigot group, also needs its
     *       switch under {@code servers:} in {@code cloud.yml} (see {@link #switchOf});</li>
     *   <li>the database template is never a service of the node (the database module runs it);</li>
     *   <li>any other group is the operator's own and counts as wanted; whether it runs is decided
     *       by its task.</li>
     * </ul>
     * This overload does not look at {@code legacy-proxy.enabled}; the node uses
     * {@link #isWanted(GroupConfig, CloudConfig)}.
     *
     * @param group  the group
     * @param wanted the switches from {@code cloud.yml}
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
        return kind.get() != ServerKind.DATABASE && wanted.isWanted(kind.get());
    }

    /**
     * Like {@link #isWanted(GroupConfig, WantedServers)}, and the 1.8.8 part of the network (the
     * legacy proxy, every PandaSpigot group and every group on the {@code Legacy-Server} template)
     * only while {@code legacy-proxy.enabled} is set. This is the rule for planning, for starts,
     * for the proxy lists and for {@link ServiceManager#stopUnwanted()} after a reload.
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return whether services of the group may be created
     */
    public static boolean isWanted(GroupConfig group, CloudConfig cloud) {
        if (needsLegacyProxy(group) && !cloud.legacyProxy().enabled()) {
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
        if (needsLegacyProxy(group) && !cloud.legacyProxy().enabled()) {
            return "cloud.yml legacy-proxy.enabled";
        }
        if (!group.wantedOrDefault()) {
            return "wanted: false in its group file";
        }
        return switchOf(group).map(kind -> kind == ServerKind.DATABASE
                ? "the database template runs no service"
                : "cloud.yml servers." + kind.key()).orElse("cloud.yml servers");
    }

    /** The legacy proxy and the 1.8.8 servers run only together with {@code legacy-proxy.enabled}. */
    private static boolean needsLegacyProxy(GroupConfig group) {
        return isLegacy(group) || kindOf(group).orElse(null) == ServerKind.LEGACY_SERVER;
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

    /**
     * Tells whether a task may run on a node.
     *
     * @param task the task
     * @param node node id
     * @return true if the task lists no nodes or lists this one
     */
    public static boolean runsOn(TaskConfig task, String node) {
        return task.nodes().isEmpty() || task.nodes().contains(node);
    }

    /**
     * One service a configuration asks for.
     *
     * @param task  task name
     * @param group group name
     * @param index index within the group
     */
    public record Planned(String task, String group, int index) {
    }

    /**
     * The services that should run after the node starts: {@code min-services} of every task on
     * this node whose group exists and is wanted. Indexes are counted per group, so two tasks of
     * one group never share an id. Unwanted groups produce nothing at all.
     *
     * @param snapshot the configuration
     * @param node     id of this node
     * @return the services, in task name order
     */
    public static List<Planned> plan(ConfigSnapshot snapshot, String node) {
        List<Planned> result = new ArrayList<>();
        Map<String, Integer> next = new java.util.HashMap<>();
        for (Map.Entry<String, TaskConfig> entry : snapshot.tasks().entrySet()) {
            TaskConfig task = entry.getValue();
            GroupConfig group = snapshot.groups().get(task.group());
            if (group == null || !isWanted(group, snapshot.cloud()) || !runsOn(task, node)) {
                continue;
            }
            int count = Math.min(task.scaling().minServices(), ServiceIds.MAX_INDEX);
            for (int i = 0; i < count; i++) {
                int index = next.merge(task.group(), 1, Integer::sum);
                if (index > ServiceIds.MAX_INDEX) {
                    break;
                }
                result.add(new Planned(entry.getKey(), task.group(), index));
            }
        }
        return result;
    }

    /**
     * The folder a service works in.
     * <ul>
     *   <li>static, index 1: {@code <home>/<template>/runtime}, the folder that holds today's worlds;</li>
     *   <li>static, index n &gt; 1: {@code <home>/<template>/runtime-<n>};</li>
     *   <li>dynamic: {@code <home>/temp/services/<id>}, deleted after the stop.</li>
     * </ul>
     *
     * @param home     the cloud home
     * @param group    the group
     * @param mode     the task mode
     * @param id       service id
     * @param index    index within the group
     * @return the absolute folder
     */
    public static Path workDirectory(Path home, GroupConfig group, TaskMode mode, String id, int index) {
        Path root = home.toAbsolutePath().normalize();
        if (mode == TaskMode.DYNAMIC) {
            return root.resolve(DYNAMIC_DIR).resolve(id);
        }
        Path template = root.resolve(group.template());
        return index == 1 ? template.resolve("runtime") : template.resolve("runtime-" + index);
    }

    /**
     * Wait before restart number {@code attempt} after a crash: {@code backoff}, doubled for every
     * further attempt, at most {@code maxBackoff}.
     *
     * @param restart the policy
     * @param attempt 1 for the first restart
     * @return the wait
     */
    public static Duration backoff(RestartConfig restart, int attempt) {
        Duration base = restart.backoff().duration();
        Duration max = restart.maxBackoff().duration();
        Duration delay = base;
        for (int i = 1; i < attempt && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }
}
