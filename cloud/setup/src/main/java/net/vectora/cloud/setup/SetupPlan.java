package net.vectora.cloud.setup;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.LegacyProxyConfig;
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.operation.AcceptEula;
import net.vectora.cloud.config.operation.CompleteSetup;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutNode;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.config.operation.SetDatabase;
import net.vectora.cloud.config.operation.SetGroupDrainTimeout;
import net.vectora.cloud.config.operation.SetGroupHeap;
import net.vectora.cloud.config.operation.SetGroupWanted;
import net.vectora.cloud.config.operation.SetLanguage;
import net.vectora.cloud.config.operation.SetLegacyProxy;
import net.vectora.cloud.config.operation.SetNetworkName;
import net.vectora.cloud.config.operation.SetProxy;
import net.vectora.cloud.config.operation.SetServerWanted;
import net.vectora.cloud.config.operation.SetTaskScaling;
import net.vectora.cloud.config.validation.ConfigNames;

/**
 * Turns {@link SetupAnswers} into configuration operations, and the current configuration into the
 * answers the assistant offers as defaults. Pure functions, no I/O.
 *
 * <p>Only differences become operations, so running the assistant again and pressing Enter
 * everywhere changes nothing. {@link net.vectora.cloud.config.ConfigService#apply} then writes only the files whose content
 * changed.
 */
public final class SetupPlan {

    /** Smallest heap the assistant accepts for a group. */
    public static final MemorySize MIN_HEAP = MemorySize.ofMegabytes(256);
    /** Largest initial heap the assistant gives a new group; the rest grows on demand. */
    static final MemorySize MAX_INITIAL_HEAP = MemorySize.ofMegabytes(512);
    /** Port of an external MariaDB or MySQL server unless the operator says otherwise. */
    public static final int EXTERNAL_DATABASE_PORT = 3306;
    /** Players one minigame service is meant for. */
    static final int MINIGAME_PLAYERS = 16;

    private SetupPlan() {
    }

    /**
     * The answers that match the current configuration. Before the first setup they are the
     * suggested defaults: everything but the 1.8.8 part wanted, the database managed where MariaDB
     * runs and H2 elsewhere, and on Pterodactyl the assigned port as Java port.
     */
    public static SetupAnswers defaults(ConfigSnapshot snapshot, SetupEnvironment env) {
        CloudConfig cloud = snapshot.cloud();
        boolean first = !cloud.setupComplete();
        Map<NetworkServer, Boolean> servers = new EnumMap<>(NetworkServer.class);
        for (NetworkServer server : NetworkServer.values()) {
            boolean wanted;
            if (server == NetworkServer.LEGACY_PROXY) {
                wanted = cloud.legacyProxy() != null && cloud.legacyProxy().enabled();
            } else if (first && server == NetworkServer.LEGACY_SERVER) {
                wanted = false;
            } else {
                wanted = cloud.servers() != null && cloud.servers().isWanted(server.kind().orElseThrow());
            }
            servers.put(server, wanted);
        }
        Map<String, Boolean> switches = new TreeMap<>();
        for (String group : groupsOf(snapshot, NetworkServer.MINIGAME)) {
            switches.put(group, snapshot.groups().get(group).wantedOrDefault());
        }
        ProxyConfig proxy = cloud.proxy() == null ? ProxyConfig.defaults() : cloud.proxy();
        int javaPort = first && env.serverPort().isPresent() ? env.serverPort().getAsInt() : proxy.port();
        LegacyProxyConfig legacy = cloud.legacyProxy() == null ? LegacyProxyConfig.defaults() : cloud.legacyProxy();
        Map<String, MemorySize> heap = new TreeMap<>();
        for (NetworkServer server : NetworkServer.values()) {
            List<String> groups = groupsOf(snapshot, server);
            if (groups.isEmpty()) {
                heap.put(newGroupName(snapshot, server), server.defaultHeap());
            }
            for (String group : groups) {
                HeapConfig current = snapshot.groups().get(group).heap();
                heap.put(group, current == null || current.max() == null ? server.defaultHeap() : current.max());
            }
        }
        Map<String, ServiceCounts> services = new TreeMap<>();
        for (NetworkServer server : NetworkServer.values()) {
            if (server.isProxy()) {
                continue;
            }
            for (String task : serviceTasks(snapshot, server)) {
                TaskConfig existing = snapshot.tasks().get(task);
                services.put(task, existing == null || existing.scaling() == null ? server.defaultCounts()
                        : ServiceCounts.of(existing.scaling()));
            }
        }
        Map<String, TimeSpan> drainTimeouts = new TreeMap<>();
        snapshot.groups().forEach((name, group) -> {
            if (group.drainTimeout() != null) {
                drainTimeouts.put(name, group.drainTimeout());
            }
        });
        Optional<NodeConfig> local = snapshot.nodes().find(cloud.nodeId());
        DatabaseConfig database = cloud.database() == null ? DatabaseConfig.defaults() : cloud.database();
        if (first && database.mode() == DatabaseMode.MANAGED && !env.managedDatabase()) {
            database = switchMode(database, DatabaseMode.H2);
        }
        if (database.mode() == DatabaseMode.DOCKER && env.pterodactyl()) {
            database = switchMode(database, env.managedDatabase() ? DatabaseMode.MANAGED : DatabaseMode.H2);
        }
        Language language = first ? env.language() : cloud.language();
        return new SetupAnswers(language == null ? env.language() : language, cloud.eulaAccepted(),
                cloud.networkName() == null ? CloudConfig.defaults().networkName() : cloud.networkName(), servers, switches,
                proxy.bind() == null ? ProxyConfig.defaults().bind() : proxy.bind(), javaPort, proxy.bedrockPort(),
                legacy.port(), heap, services, drainTimeouts, local.map(NodeConfig::maxMemory).orElse(null),
                local.map(NodeConfig::reserve).orElse(null), database, Optional.empty());
    }

    /**
     * The answers to write when the files may have changed since the assistant read them: a
     * three-way merge of the settings that the API and the console can change while the assistant
     * runs. A value the operator left as {@code shown} takes the value of {@code fresh}, so a change
     * made in the meantime stays; a value the operator changed wins. That covers the heap of every
     * group, the {@code static/min/max} of every task (each count on its own), the
     * {@code drain-timeout} of every group (an entry that is gone was removed with {@code default})
     * and {@code max-memory} and each field of {@code memory-reserve} of this node. Everything else
     * is taken from {@code answers} unchanged.
     *
     * @param answers what the operator answered
     * @param shown   the defaults the assistant showed, {@link #defaults} of the files at its start
     * @param fresh   {@link #defaults} of the files now
     * @return the answers to check and write
     */
    public static SetupAnswers rebase(SetupAnswers answers, SetupAnswers shown, SetupAnswers fresh) {
        Objects.requireNonNull(answers, "answers");
        Objects.requireNonNull(shown, "shown");
        Objects.requireNonNull(fresh, "fresh");
        Map<String, MemorySize> heap = new TreeMap<>(answers.heap());
        heap.replaceAll((group, value) -> pick(value, shown.heap().get(group), fresh.heap().get(group),
                fresh.heap().containsKey(group)));
        Map<String, ServiceCounts> services = new TreeMap<>(answers.services());
        services.replaceAll((task, value) -> {
            ServiceCounts before = shown.services().get(task);
            ServiceCounts now = fresh.services().get(task);
            if (before == null || now == null) {
                return value;
            }
            return new ServiceCounts(
                    value.staticServices() == before.staticServices() ? now.staticServices() : value.staticServices(),
                    value.minServices() == before.minServices() ? now.minServices() : value.minServices(),
                    value.maxServices() == before.maxServices() ? now.maxServices() : value.maxServices());
        });
        Map<String, TimeSpan> drainTimeouts = new TreeMap<>();
        Set<String> groups = new TreeSet<>(answers.drainTimeouts().keySet());
        groups.addAll(shown.drainTimeouts().keySet());
        groups.addAll(fresh.drainTimeouts().keySet());
        for (String group : groups) {
            // A missing entry means "no drain-timeout", so the merge runs on the nullable values.
            TimeSpan value = pick(answers.drainTimeouts().get(group), shown.drainTimeouts().get(group),
                    fresh.drainTimeouts().get(group), true);
            if (value != null) {
                drainTimeouts.put(group, value);
            }
        }
        MemorySize maxMemory = pick(answers.maxMemory(), shown.maxMemory(), fresh.maxMemory(), true);
        MemoryReserve answered = orNone(answers.memoryReserve());
        MemoryReserve before = orNone(shown.memoryReserve());
        MemoryReserve now = orNone(fresh.memoryReserve());
        MemoryReserve reserve = new MemoryReserve(
                pick(answered.system(), before.system(), now.system(), true),
                pick(answered.nodeOverhead(), before.nodeOverhead(), now.nodeOverhead(), true),
                pick(answered.database(), before.database(), now.database(), true));
        return new SetupAnswers(answers.language(), answers.eulaAccepted(), answers.networkName(), answers.servers(),
                answers.groupSwitches(), answers.bind(), answers.javaPort(), answers.bedrockPort(), answers.legacyPort(),
                heap, services, drainTimeouts, maxMemory, reserve.noneSet() ? null : reserve, answers.database(),
                answers.databasePassword());
    }

    /** {@code answer} if the operator changed it from {@code shown}, else {@code fresh} if there is one. */
    private static <T> T pick(T answer, T shown, T fresh, boolean hasFresh) {
        return Objects.equals(answer, shown) && hasFresh ? fresh : answer;
    }

    private static MemoryReserve orNone(MemoryReserve reserve) {
        return reserve == null ? new MemoryReserve(null, null, null) : reserve;
    }

    /**
     * The tasks the assistant asks the service counts of for {@code server}: the existing tasks of
     * its groups, sorted, or the default task it will create (named like the new group). Empty if
     * groups exist but none has a task.
     */
    public static List<String> serviceTasks(ConfigSnapshot snapshot, NetworkServer server) {
        List<String> groups = groupsOf(snapshot, server);
        if (groups.isEmpty()) {
            return List.of(newGroupName(snapshot, server));
        }
        List<String> tasks = new ArrayList<>();
        snapshot.tasks().forEach((name, task) -> {
            if (groups.contains(task.group())) {
                tasks.add(name);
            }
        });
        tasks.sort(null);
        return tasks;
    }

    /** Names of the groups that use the template of {@code server}, sorted. */
    public static List<String> groupsOf(ConfigSnapshot snapshot, NetworkServer server) {
        List<String> names = new ArrayList<>();
        snapshot.groups().forEach((name, group) -> {
            if (server.template().equals(group.template())) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * The groups the assistant asks about for {@code server}: the existing ones, or the default group
     * it will create.
     */
    public static List<String> heapGroups(ConfigSnapshot snapshot, NetworkServer server) {
        List<String> existing = groupsOf(snapshot, server);
        return existing.isEmpty() ? List.of(newGroupName(snapshot, server)) : existing;
    }

    /**
     * The operations that bring {@code current} to {@code answers}, without accepting the EULA and
     * without marking the setup complete; {@link #finish} does that afterwards.
     */
    public static List<ConfigOperation> operations(ConfigSnapshot current, SetupAnswers answers) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(answers, "answers");
        CloudConfig cloud = current.cloud();
        List<ConfigOperation> ops = new ArrayList<>();
        if (cloud.language() != answers.language()) {
            ops.add(new SetLanguage(answers.language()));
        }
        if (!answers.networkName().equals(cloud.networkName())) {
            ops.add(new SetNetworkName(answers.networkName()));
        }
        for (NetworkServer server : NetworkServer.values()) {
            Optional<ServerKind> kind = server.kind();
            if (kind.isPresent() && (cloud.servers() == null || cloud.servers().isWanted(kind.get()) != answers.wants(server))) {
                ops.add(new SetServerWanted(kind.get(), answers.wants(server)));
            }
        }
        boolean databaseServer = isLocal(answers.database().mode());
        if (cloud.servers() == null || cloud.servers().isWanted(ServerKind.DATABASE) != databaseServer) {
            ops.add(new SetServerWanted(ServerKind.DATABASE, databaseServer));
        }
        LegacyProxyConfig legacy = new LegacyProxyConfig(answers.wants(NetworkServer.LEGACY_PROXY), answers.legacyPort());
        if (!legacy.equals(cloud.legacyProxy())) {
            ops.add(new SetLegacyProxy(legacy.enabled(), legacy.port()));
        }
        ProxyConfig proxy = new ProxyConfig(answers.bind(), answers.javaPort(), answers.bedrockPort());
        if (!proxy.equals(cloud.proxy())) {
            ops.add(new SetProxy(proxy));
        }
        if (!answers.database().equals(cloud.database())) {
            ops.add(new SetDatabase(answers.database()));
        }
        Optional<NodeConfig> local = current.nodes().find(cloud.nodeId());
        if (local.isPresent()) {
            NodeConfig node = local.get().withMaxMemory(answers.maxMemory()).withMemoryReserve(answers.memoryReserve());
            if (!node.equals(local.get())) {
                ops.add(new PutNode(node));
            }
        }
        Set<String> taken = new TreeSet<>(current.groups().keySet());
        taken.addAll(current.tasks().keySet());
        for (NetworkServer server : NetworkServer.values()) {
            List<String> existing = groupsOf(current, server);
            if (existing.isEmpty()) {
                if (answers.wants(server)) {
                    String name = newGroupName(taken, server);
                    taken.add(name);
                    ops.add(new PutGroup(name, newGroup(server, answers.heap().getOrDefault(name, server.defaultHeap()))
                            .withDrainTimeout(answers.drainTimeouts().get(name))));
                    ops.add(new PutTask(name, newTask(server, name,
                            server.isProxy() ? server.defaultCounts() : answers.services().getOrDefault(name, server.defaultCounts()))));
                }
                continue;
            }
            if (!server.isProxy()) {
                for (String task : serviceTasks(current, server)) {
                    ServiceCounts counts = answers.services().get(task);
                    TaskConfig before = current.tasks().get(task);
                    if (counts != null && before != null && before.scaling() != null
                            && !counts.equals(ServiceCounts.of(before.scaling()))) {
                        ops.add(new SetTaskScaling(task, counts.applyTo(before.scaling())));
                    }
                }
            }
            for (String group : existing) {
                MemorySize max = answers.heap().get(group);
                HeapConfig heap = current.groups().get(group).heap();
                if (max != null && (heap == null || !max.equals(heap.max()))) {
                    MemorySize min = heap == null || heap.min() == null ? initialHeap(max) : smaller(heap.min(), max);
                    ops.add(new SetGroupHeap(group, min, max));
                }
                TimeSpan drain = answers.drainTimeouts().get(group);
                // The defaults hold every value of the files, so a missing entry was removed on purpose.
                if (!Objects.equals(drain, current.groups().get(group).drainTimeout())) {
                    ops.add(new SetGroupDrainTimeout(group, drain));
                }
            }
        }
        answers.groupSwitches().forEach((group, wanted) -> {
            GroupConfig existing = current.groups().get(group);
            if (existing != null && existing.wantedOrDefault() != wanted) {
                ops.add(new SetGroupWanted(group, wanted));
            }
        });
        return List.copyOf(ops);
    }

    /** What marks the setup as done: the EULA and {@code setup-complete}, both only if not set yet. */
    public static List<ConfigOperation> finish(ConfigSnapshot current) {
        List<ConfigOperation> ops = new ArrayList<>();
        if (!current.cloud().eulaAccepted()) {
            ops.add(new AcceptEula());
        }
        if (!current.cloud().setupComplete()) {
            ops.add(new CompleteSetup());
        }
        return List.copyOf(ops);
    }

    /** The configuration after {@link #operations} and {@link #finish}, for checks and the summary. */
    public static ConfigSnapshot preview(ConfigSnapshot current, SetupAnswers answers) throws ConfigException {
        ConfigSnapshot after = current;
        for (ConfigOperation op : operations(current, answers)) {
            after = op.apply(after);
        }
        if (answers.eulaAccepted()) {
            for (ConfigOperation op : finish(after)) {
                after = op.apply(after);
            }
        }
        return after;
    }

    /**
     * {@code current} in another mode. Switching to the managed or Docker server moves host and port
     * to the cloud's own defaults ({@code 127.0.0.1:3307}); switching from them to an external server
     * sets the usual MariaDB port 3306. Database and user name stay.
     */
    public static DatabaseConfig switchMode(DatabaseConfig current, DatabaseMode mode) {
        DatabaseConfig moved = current.withMode(mode);
        boolean local = isLocal(mode);
        boolean wasLocal = isLocal(current.mode());
        if (local && !wasLocal) {
            DatabaseConfig defaults = DatabaseConfig.defaults();
            return new DatabaseConfig(mode, defaults.host(), defaults.port(), moved.name(), moved.user(), moved.dockerImage());
        }
        if (mode == DatabaseMode.EXTERNAL && wasLocal) {
            return new DatabaseConfig(mode, moved.host(), EXTERNAL_DATABASE_PORT, moved.name(), moved.user(), null);
        }
        return moved;
    }

    private static boolean isLocal(DatabaseMode mode) {
        return mode == DatabaseMode.MANAGED || mode == DatabaseMode.DOCKER;
    }

    /** Files that differ between two snapshots, relative to the home, sorted. */
    public static Set<String> changedFiles(ConfigSnapshot before, ConfigSnapshot after) {
        Set<String> files = new TreeSet<>();
        if (!Objects.equals(before.cloud(), after.cloud())) {
            files.add(CloudHome.CLOUD_FILE);
        }
        if (!Objects.equals(before.nodes(), after.nodes())) {
            files.add(CloudHome.NODES_FILE);
        }
        Set<String> groups = new TreeSet<>(before.groups().keySet());
        groups.addAll(after.groups().keySet());
        for (String group : groups) {
            if (!Objects.equals(before.groups().get(group), after.groups().get(group))) {
                files.add(CloudHome.groupFileName(group));
            }
        }
        Set<String> tasks = new TreeSet<>(before.tasks().keySet());
        tasks.addAll(after.tasks().keySet());
        for (String task : tasks) {
            if (!Objects.equals(before.tasks().get(task), after.tasks().get(task))) {
                files.add(CloudHome.taskFileName(task));
            }
        }
        return files;
    }

    /** The name the default group of {@code server} gets: its key, or the key with a number if that is taken. */
    static String newGroupName(ConfigSnapshot snapshot, NetworkServer server) {
        Set<String> taken = new TreeSet<>(snapshot.groups().keySet());
        taken.addAll(snapshot.tasks().keySet());
        return newGroupName(taken, server);
    }

    private static String newGroupName(Set<String> taken, NetworkServer server) {
        if (!taken.contains(server.key())) {
            return server.key();
        }
        for (int i = 2; ; i++) {
            String name = server.key() + "-" + i;
            if (!taken.contains(name) && ConfigNames.isValidName(name)) {
                return name;
            }
        }
    }

    /** The group the assistant creates for {@code server}. */
    static GroupConfig newGroup(NetworkServer server, MemorySize max) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, server.template(), server.software(), server.minecraftVersion(),
                JavaSelection.AUTO, new HeapConfig(initialHeap(max), max), List.of(), server.combat());
    }

    /**
     * The task the assistant creates for {@code server} with its suggested counts
     * ({@link NetworkServer#defaultCounts()}).
     */
    static TaskConfig newTask(NetworkServer server, String group) {
        return newTask(server, group, server.defaultCounts());
    }

    /**
     * The task the assistant creates for {@code server}: the given counts, scale up at
     * {@value ScalingConfig#DEFAULT_SCALE_UP_AT} %, down below {@value ScalingConfig#DEFAULT_SCALE_DOWN_BELOW} %;
     * a minigame round is meant for 16 players, every other server for 50.
     */
    static TaskConfig newTask(NetworkServer server, String group, ServiceCounts counts) {
        ScalingConfig scaling = ScalingConfig.counts(counts.staticServices(), counts.minServices(), counts.maxServices());
        if (server == NetworkServer.MINIGAME) {
            scaling = scaling.withPlayersPerService(MINIGAME_PLAYERS);
        }
        return new TaskConfig(TaskConfig.SCHEMA_VERSION, group, scaling, List.of());
    }

    /** Initial heap of a new group: the maximum, but at most {@link #MAX_INITIAL_HEAP}. */
    static MemorySize initialHeap(MemorySize max) {
        return smaller(MAX_INITIAL_HEAP, max);
    }

    private static MemorySize smaller(MemorySize a, MemorySize b) {
        return a.megabytes() <= b.megabytes() ? a : b;
    }
}
