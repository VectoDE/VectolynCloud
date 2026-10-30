package net.vectolyn.cloud.node.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.GroupRules;
import net.vectolyn.cloud.config.model.RestartConfig;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.config.model.WantedServers;

/**
 * Pure decisions of the service core, without any I/O: which groups are wanted, which services a
 * configuration asks for, where each service works, and how long to wait before a restart.
 */
public final class ServicePlanner {

    /** Folder below the home where dynamic services work. */
    public static final String DYNAMIC_DIR = CloudHome.DYNAMIC_SERVICES_DIR;

    /** Template folder of the legacy proxy (Velocity with {@code bungeeguard}, in front of the 1.8.8 groups). */
    public static final String LEGACY_PROXY_TEMPLATE = GroupRules.LEGACY_PROXY_TEMPLATE;

    private ServicePlanner() {
    }

    /**
     * The server kind whose template a group uses ({@link GroupRules#kindOf}).
     *
     * @param group the group
     * @return the kind, or empty for a template that is none of the known folders
     */
    public static Optional<ServerKind> kindOf(GroupConfig group) {
        return GroupRules.kindOf(group);
    }

    /**
     * The switch under {@code servers:} that a group follows ({@link GroupRules#switchOf}).
     *
     * @param group the group
     * @return the switch, or empty for a group of the operator's own that no switch covers
     */
    public static Optional<ServerKind> switchOf(GroupConfig group) {
        return GroupRules.switchOf(group);
    }

    /**
     * Whether a group is wanted by its own switch and by the switch of its server kind, without
     * {@code legacy-proxy.enabled} ({@link GroupRules#isWanted(GroupConfig, WantedServers)}).
     *
     * @param group  the group
     * @param wanted the switches from {@code cloud.yml}
     * @return whether services of the group may be created
     */
    public static boolean isWanted(GroupConfig group, WantedServers wanted) {
        return GroupRules.isWanted(group, wanted);
    }

    /**
     * The rule for planning, for starts, for the proxy lists and for
     * {@link ServiceManager#stopUnwanted()} after a reload
     * ({@link GroupRules#isWanted(GroupConfig, CloudConfig)}); the API reports the same as
     * {@code runs}.
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return whether services of the group may be created
     */
    public static boolean isWanted(GroupConfig group, CloudConfig cloud) {
        return GroupRules.isWanted(group, cloud);
    }

    /**
     * Why a group is not wanted, for messages ({@link GroupRules#unwantedBecause}).
     *
     * @param group the group
     * @param cloud {@code cloud.yml}
     * @return the setting that switches it off, for example {@code servers.lobby}
     */
    public static String unwantedBecause(GroupConfig group, CloudConfig cloud) {
        return GroupRules.unwantedBecause(group, cloud);
    }

    /**
     * Whether a group belongs to the 1.8.8 part of the network ({@link GroupRules#isLegacy}).
     *
     * @param group the group
     * @return whether it runs behind or as the legacy proxy
     */
    public static boolean isLegacy(GroupConfig group) {
        return GroupRules.isLegacy(group);
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
     * @param mode  static for the first {@code static-services} of the task, dynamic for the rest
     */
    public record Planned(String task, String group, int index, TaskMode mode) {
    }

    /**
     * The services that should run after the node starts: {@code min-services} of every task on
     * this node whose group exists and is wanted, the first {@code static-services} of them static.
     * Indexes are counted per group, so two tasks of one group never share an id. Unwanted groups
     * produce nothing at all.
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
                TaskMode mode = i < task.scaling().staticServices() ? TaskMode.STATIC : TaskMode.DYNAMIC;
                result.add(new Planned(entry.getKey(), task.group(), index, mode));
            }
        }
        return result;
    }

    /**
     * The folder a static service works in: {@code <home>/local/services/<id>}, kept across stops
     * (worlds, configs).
     *
     * @param home the cloud home
     * @param id   service id, for example {@code lobby-1}
     * @return the absolute folder
     */
    public static Path staticDirectory(Path home, String id) {
        return home.toAbsolutePath().normalize().resolve(STATIC_DIR).resolve(id);
    }

    /**
     * The folder one run of a dynamic service works in: {@code <home>/temp/services/<id>_<run>},
     * deleted after the stop. Each start gets a new {@code run}, so a folder that is still being
     * deleted never meets the next start of the same id.
     *
     * @param home the cloud home
     * @param id   service id, for example {@code minigame-3}
     * @param run  the run, see {@link #newRun()}
     * @return the absolute folder
     */
    public static Path dynamicDirectory(Path home, String id, String run) {
        if (run == null || !RUN.matcher(run).matches()) {
            throw new IllegalArgumentException("a run is 8 lower-case hex digits");
        }
        return home.toAbsolutePath().normalize().resolve(DYNAMIC_DIR).resolve(id + RUN_SEPARATOR + run);
    }

    /**
     * Whether a folder below {@code temp/services/} belongs to a run of the service {@code id}.
     *
     * @param folder the folder
     * @param id     service id
     * @return whether its name is {@code <id>_<run>}
     */
    public static boolean isRunOf(Path folder, String id) {
        Path name = folder.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        return text.startsWith(id + RUN_SEPARATOR) && RUN.matcher(text.substring(id.length() + 1)).matches();
    }

    /**
     * A new run name for a dynamic service: 8 random lower-case hex digits.
     *
     * @return the run
     */
    public static String newRun() {
        return String.format(java.util.Locale.ROOT, "%08x", RANDOM.nextInt());
    }

    /** Folder below the home where static services work. */
    public static final String STATIC_DIR = CloudHome.STATIC_SERVICES_DIR;

    private static final String RUN_SEPARATOR = "_";
    private static final java.util.regex.Pattern RUN = java.util.regex.Pattern.compile("[0-9a-f]{8}");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

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
