package net.vectora.cloud.console.builtin;

import java.util.List;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigChanged;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.validation.ConfigNames;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.command.Argument;
import net.vectora.cloud.console.command.Command;

/**
 * {@code node list|info|set}: the nodes of {@code nodes.yml} and their memory settings. Only the
 * memory fields can be changed here; host, ports and adding or removing nodes stay in the file.
 * Every change goes through {@link ConfigService#apply} like any other.
 */
final class NodeCommands {

    /** Value of {@code max-memory} that removes the limit (worked out by the node). */
    static final String AUTO = "auto";
    /** Value of a reserve field that removes it, so its default applies. */
    static final String DEFAULT_VALUE = "default";

    private static final List<String> FIELDS = List.of("max-memory", "memory-reserve.system",
            "memory-reserve.node-overhead", "memory-reserve.database");

    private NodeCommands() {
    }

    static Command node(ConfigService config) {
        Argument id = Argument.required("id", Text.of("Node id", "Node-ID"))
                .suggest(previous -> config.snapshot().nodes().nodes().stream().map(NodeConfig::id).toList());
        return Command.builder("node")
                .description(Text.of("Shows the nodes (nodes.yml) and changes their memory settings.",
                        "Zeigt die Nodes (nodes.yml) und ändert ihre Speichereinstellungen."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Lists every node.", "Listet alle Nodes auf."))
                        .executes(context -> list(context.out(), config.snapshot())))
                .subcommand(Command.builder("info")
                        .description(Text.of("Shows one node.", "Zeigt einen Node."))
                        .argument(id)
                        .executes(context -> info(context.out(), config.snapshot(), context.argument("id"))))
                .subcommand(Command.builder("set")
                        .description(Text.of("Changes a memory field of a node.", "Ändert ein Speicherfeld eines Nodes."))
                        .argument(id)
                        .argument(Argument.required("field", Text.of(String.join(", ", FIELDS), String.join(", ", FIELDS)))
                                .suggest(FIELDS))
                        .argument(Argument.required("value", Text.of(
                                "Size like 8G or 512M; max-memory: auto removes the limit; reserve: default",
                                "Größe wie 8G oder 512M; max-memory: auto entfernt die Grenze; Reserve: default")))
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
                .build();
    }

    /** The node {@code id} with {@code field} set to {@code value}. */
    static NodeConfig change(NodesConfig nodes, String id, String field, String value) throws ConfigException {
        int index = index(nodes, id);
        NodeConfig node = nodes.nodes().get(index);
        String path = "nodes[" + index + "]." + field;
        MemoryReserve reserve = node.reserve() == null ? new MemoryReserve(null, null, null) : node.reserve();
        return switch (field) {
            case "max-memory" -> node.withMaxMemory(AUTO.equals(value) ? null : size(path, value, AUTO));
            case "memory-reserve.system" -> node.withMemoryReserve(reserve.withSystem(reserveSize(path, value)));
            case "memory-reserve.node-overhead" -> node.withMemoryReserve(reserve.withNodeOverhead(reserveSize(path, value)));
            case "memory-reserve.database" -> node.withMemoryReserve(reserve.withDatabase(reserveSize(path, value)));
            default -> throw new ConfigException(ConfigError.of(CloudHome.NODES_FILE, "", ErrorCode.INVALID_VALUE,
                    String.join(", ", FIELDS)));
        };
    }

    private static MemorySize reserveSize(String path, String value) throws ConfigException {
        return DEFAULT_VALUE.equals(value) ? null : size(path, value, DEFAULT_VALUE);
    }

    private static MemorySize size(String path, String value, String keyword) throws ConfigException {
        try {
            return MemorySize.parse(value);
        } catch (IllegalArgumentException e) {
            throw Values.invalid(CloudHome.NODES_FILE, path, MemorySize.FORMAT + ", or " + keyword);
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
        MemoryReserve raw = n.reserve() == null ? new MemoryReserve(null, null, null) : n.reserve();
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
        return n.maxMemory() == null ? AUTO : n.maxMemory().toString();
    }

    private static String marker(MemorySize raw) {
        return raw == null ? " (default)" : "";
    }
}
