// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.console.builtin;

import java.util.List;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigChanged;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.MemoryReserve;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.PortRange;
import net.vectolyn.cloud.config.operation.ConfigOperation;
import net.vectolyn.cloud.config.operation.PutNode;
import net.vectolyn.cloud.config.operation.RemoveNode;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import org.jspecify.annotations.Nullable;

/**
 * {@code node list|info|create|set|delete}: the nodes of {@code nodes.yml}, their addresses, ports
 * and memory settings. Every change goes through {@link ConfigService#apply} like any other, so it
 * is checked like a hand-edited file. cloud-node adds {@code node status} (version, Java, uptime,
 * heap and services of the running node) as an extra subcommand.
 */
final class NodeCommands {

    /** Value of {@code max-memory} that removes the limit (worked out by the node). */
    static final String AUTO = "auto";
    /** Value of a reserve field that removes it, so its default applies. */
    static final String DEFAULT_VALUE = "default";

    private static final List<String> FIELDS = List.of("host", "port", "service-ports", "max-memory",
            "memory-reserve.system", "memory-reserve.node-overhead", "memory-reserve.database");
    /** Form of a port range on the command line. */
    static final String PORT_RANGE_FORMAT = "first-last, for example 30000-30999";

    private NodeCommands() {
    }

    static Command node(ConfigService config, List<Command> extra) {
        Argument id = Argument.required("id", Text.of("Node id", "Node-ID"))
                .suggest(previous -> config.snapshot().nodes().nodes().stream().map(NodeConfig::id).toList());
        Command.Builder command = Command.builder("node")
                .description(Text.of("Shows, adds, changes and removes the nodes (nodes.yml).",
                        "Zeigt, ergänzt, ändert und entfernt die Nodes (nodes.yml)."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Lists every node.", "Listet alle Nodes auf."))
                        .executes(context -> list(context.out(), config.snapshot())))
                .subcommand(Command.builder("info")
                        .description(Text.of("Shows one node.", "Zeigt einen Node."))
                        .argument(id)
                        .executes(context -> info(context.out(), config.snapshot(), context.argument("id"))))
                .subcommand(Command.builder("create")
                        .description(Text.of("Adds a node to nodes.yml; memory settings start at their defaults.",
                                "Trägt einen Node in nodes.yml ein; die Speichereinstellungen starten mit ihren Standards."))
                        .argument(Argument.required("id", Text.of("New node id, for example node-2",
                                "Neue Node-ID, zum Beispiel node-2")))
                        .argument(Argument.required("host", Text.of("Address the other nodes reach it at",
                                "Adresse, unter der die anderen Nodes ihn erreichen")))
                        .argument(Argument.required("port", Text.of("Port of the cloud channel, for example 7010",
                                "Port des Cloud-Kanals, zum Beispiel 7010")))
                        .argument(Argument.required("service-ports", Text.of("Ports for its services, " + PORT_RANGE_FORMAT,
                                "Ports für seine Dienste, erster-letzter, zum Beispiel 30000-30999")))
                        .executes(context -> {
                            String node = context.argument("id");
                            NodeConfig value = new NodeConfig(node, context.argument("host"),
                                    Values.integer(CloudHome.NODES_FILE, "port", context.argument("port")),
                                    portRange(CloudHome.NODES_FILE, "service-ports", context.argument("service-ports")));
                            ConfigChanged change = config.apply(List.of(requireNew(node), new PutNode(value)));
                            BuiltinCommands.report(context.out(), change, CloudHome.NODES_FILE + " " + node);
                        }))
                .subcommand(Command.builder("set")
                        .description(Text.of("Changes one field of a node.", "Ändert ein Feld eines Nodes."))
                        .argument(id)
                        .argument(Argument.required("field", Text.of(String.join(", ", FIELDS), String.join(", ", FIELDS)))
                                .suggest(FIELDS))
                        .argument(Argument.required("value", Text.of(
                                "host: address; port: 1 to 65535; service-ports: first-last; size like 8G or 512M, "
                                        + "max-memory: auto removes the limit, reserve: default",
                                "host: Adresse; port: 1 bis 65535; service-ports: erster-letzter; Größe wie 8G oder 512M, "
                                        + "max-memory: auto entfernt die Grenze, Reserve: default")))
                        .executes(context -> {
                            String node = context.argument("id");
                            String field = context.argument("field");
                            String value = context.argument("value");
                            ConfigOperation operation = snapshot -> snapshot.withNodes(
                                    snapshot.nodes().withNode(change(snapshot.nodes(), node, field, value)));
                            ConfigChanged change = config.apply(operation);
                            BuiltinCommands.report(context.out(), change, CloudHome.NODES_FILE + " " + node + ": " + field
                                    + " = " + value);
                        }))
                .subcommand(Command.builder("delete")
                        .aliases("remove")
                        .description(Text.of("Removes a node from nodes.yml after asking; cloud.yml and no task may "
                                + "name it.", "Entfernt einen Node nach Rückfrage aus nodes.yml; cloud.yml und kein Task "
                                + "dürfen ihn nennen."))
                        .argument(id)
                        .argument(GroupCommands.confirmation())
                        .executes(context -> {
                            String node = context.argument("id");
                            RemoveNode removal = new RemoveNode(node);
                            String what = CloudHome.NODES_FILE + " " + ConfigNames.safeArgument(node);
                            if (!GroupCommands.confirmDelete(context, config.snapshot(), removal, what)) {
                                return;
                            }
                            GroupCommands.reportDeleted(context.out(), config.apply(removal), what);
                        }));
        extra.forEach(command::subcommand);
        return command.build();
    }

    /** The node {@code id} with {@code field} set to {@code value}. */
    static NodeConfig change(NodesConfig nodes, String id, String field, String value) throws ConfigException {
        int index = index(nodes, id);
        NodeConfig node = nodes.nodes().get(index);
        String path = "nodes[" + index + "]." + field;
        MemoryReserve reserve = node.reserve() == null ? noReserve() : node.reserve();
        return switch (field) {
            case "host" -> new NodeConfig(node.id(), value, node.port(), node.servicePorts(), node.maxMemory(),
                    node.reserve());
            case "port" -> new NodeConfig(node.id(), node.host(), Values.integer(CloudHome.NODES_FILE, path, value),
                    node.servicePorts(), node.maxMemory(), node.reserve());
            case "service-ports" -> new NodeConfig(node.id(), node.host(), node.port(),
                    portRange(CloudHome.NODES_FILE, path, value), node.maxMemory(), node.reserve());
            case "max-memory" -> node.withMaxMemory(Values.unset(AUTO.equals(value) ? null : size(path, value, AUTO)));
            case "memory-reserve.system" ->
                    node.withMemoryReserve(reserve.withSystem(Values.unset(reserveSize(path, value))));
            case "memory-reserve.node-overhead" ->
                    node.withMemoryReserve(reserve.withNodeOverhead(Values.unset(reserveSize(path, value))));
            case "memory-reserve.database" ->
                    node.withMemoryReserve(reserve.withDatabase(Values.unset(reserveSize(path, value))));
            default -> throw new ConfigException(ConfigError.of(CloudHome.NODES_FILE, "", ErrorCode.INVALID_VALUE,
                    String.join(", ", FIELDS)));
        };
    }

    /** A reserve with nothing set. */
    private static MemoryReserve noReserve() {
        return new MemoryReserve(Values.unset(null), Values.unset(null), Values.unset(null));
    }

    /** {@code first-last}, for example {@code 30000-30999}; order and range are checked by the validator. */
    static PortRange portRange(String file, String path, String value) throws ConfigException {
        if (!value.matches("[0-9]{1,5}-[0-9]{1,5}")) {
            throw Values.invalid(file, path, PORT_RANGE_FORMAT);
        }
        int dash = value.indexOf('-');
        try {
            return new PortRange(Integer.parseInt(value.substring(0, dash)),
                    Integer.parseInt(value.substring(dash + 1)));
        } catch (NumberFormatException e) {
            throw Values.invalid(file, path, PORT_RANGE_FORMAT, e);
        }
    }

    private static ConfigOperation requireNew(String id) {
        return snapshot -> {
            if (snapshot.nodes().find(id).isPresent()) {
                throw new ConfigException(ConfigError.of(CloudHome.NODES_FILE, "nodes", ErrorCode.ALREADY_EXISTS,
                        ConfigNames.safeArgument(id)));
            }
            return snapshot;
        };
    }

    private static @Nullable MemorySize reserveSize(String path, String value) throws ConfigException {
        return DEFAULT_VALUE.equals(value) ? null : size(path, value, DEFAULT_VALUE);
    }

    private static MemorySize size(String path, String value, String keyword) throws ConfigException {
        try {
            return MemorySize.parse(value);
        } catch (IllegalArgumentException e) {
            throw Values.invalid(CloudHome.NODES_FILE, path, MemorySize.FORMAT + ", or " + keyword, e);
        }
    }

    private static int index(NodesConfig nodes, String id) throws ConfigException {
        for (int i = 0; i < nodes.nodes().size(); i++) {
            if (id.equals(nodes.nodes().get(i).id())) {
                return i;
            }
        }
        throw new ConfigException(ConfigError.of(CloudHome.NODES_FILE, "", ErrorCode.UNKNOWN_NODE,
                ConfigNames.safeArgument(id)));
    }

    private static void list(ConsoleOutput out, ConfigSnapshot snapshot) {
        List<NodeConfig> nodes = snapshot.nodes().nodes();
        int width = nodes.stream().mapToInt(n -> String.valueOf(n.id()).length()).max().orElse(0);
        for (NodeConfig n : nodes) {
            out.println(BuiltinCommands.pad(String.valueOf(n.id()), width) + "  " + n.host() + ":" + n.port()
                    + "  max-memory " + maxMemory(n) + "  " + (n.id() != null && n.id().equals(snapshot.cloud().nodeId())
                    ? Text.of("(this node)", "(dieser Node)").get(out.language()) : ""));
        }
    }

    private static void info(ConsoleOutput out, ConfigSnapshot snapshot, String id) throws ConfigException {
        NodeConfig n = snapshot.nodes().nodes().get(index(snapshot.nodes(), id));
        MemoryReserve reserve = n.reserve();
        MemoryReserve raw = reserve == null ? noReserve() : reserve;
        MemoryReserve full = n.memoryReserve();
        out.heading(CloudHome.NODES_FILE + " " + n.id());
        out.println("  host                         = " + n.host());
        out.println("  port                         = " + n.port());
        out.println("  service-ports                = " + n.servicePorts());
        out.println("  max-memory                   = " + maxMemory(n));
        out.println("  memory-reserve.system        = " + full.system() + marker(raw.system()));
        out.println("  memory-reserve.node-overhead = " + full.nodeOverhead() + marker(raw.nodeOverhead()));
        out.println("  memory-reserve.database      = " + full.database() + marker(raw.database()));
    }

    private static String maxMemory(NodeConfig n) {
        MemorySize max = n.maxMemory();
        return max == null ? AUTO : max.toString();
    }

    private static String marker(@Nullable MemorySize raw) {
        return raw == null ? " (default)" : "";
    }
}
