package net.vectora.cloud.node.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ServerKind;
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

    private ServicePlanner() {
    }

    /**
     * The server kind whose template a group uses.
     *
     * @param group the group
     * @return the kind, or empty for a template that is none of the eight known folders
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
     * Tells whether a group is wanted. A group on one of the eight known templates follows the
     * "wanted" switch of its server kind in {@code cloud.yml}. The database template is never a
     * service of the node (the database module runs it). A group on any other template is the
     * operator's own and counts as wanted; whether it runs is decided by its task.
     *
     * @param group  the group
     * @param wanted the switches from {@code cloud.yml}
     * @return whether services of the group may be created
     */
    public static boolean isWanted(GroupConfig group, WantedServers wanted) {
        Optional<ServerKind> kind = kindOf(group);
        if (kind.isEmpty()) {
            return true;
        }
        return kind.get() != ServerKind.DATABASE && wanted.isWanted(kind.get());
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
            if (group == null || !isWanted(group, snapshot.cloud().servers()) || !runsOn(task, node)) {
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
