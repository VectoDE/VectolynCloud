package net.vectolyn.cloud.console.builtin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigChanged;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.ScalingConfig;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.config.model.TimeSpan;
import net.vectolyn.cloud.config.operation.ConfigOperation;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.cloud.config.operation.RemoveGroup;
import net.vectolyn.cloud.config.operation.RemoveTask;
import net.vectolyn.cloud.config.operation.SetServerWanted;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.config.validation.ConfigValidator;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandContext;
import net.vectolyn.cloud.console.command.CommandException;
import net.vectolyn.cloud.console.prompt.PromptCancelledException;
import net.vectolyn.cloud.console.prompt.Question;

/**
 * {@code group} and {@code task}: list, show, create, change, delete and switch {@code wanted}.
 * Every change is a {@link ConfigOperation} applied through {@link ConfigService}, so it is
 * validated and written exactly like the setup assistant's or a hand edit.
 *
 * <p>{@code wanted} is stored per server kind in {@code cloud.yml} ({@code servers:}). A group
 * belongs to the kind whose template folder it uses ({@code Lobby-Server} is {@code lobby}); a task
 * to the kind of its group. Switching it affects every group with that template.
 */
final class GroupCommands {

    static final MemorySize DEFAULT_HEAP_MIN = MemorySize.ofMegabytes(512);
    static final MemorySize DEFAULT_HEAP_MAX = MemorySize.ofMegabytes(1024);

    private static final List<String> GROUP_FIELDS = List.of("template", "software", "minecraft-version", "java",
            "heap.min", "heap.max", "combat", "drain-timeout");
    /** Value of {@code group set <name> drain-timeout} that removes the field, so the default applies. */
    static final String DEFAULT_VALUE = "default";
    private static final List<String> TASK_FIELDS = List.of("group", "mode", "static-services", "min-services",
            "max-services", "players-per-service", "scale-up-at", "scale-down-below", "scale-down-after", "cooldown",
            "nodes");
    private static final List<String> ON_OFF = List.of("on", "off");
    private static final List<String> YES_FLAGS = List.of("--yes", "-y");

    private GroupCommands() {
    }

    static Command group(ConfigService config) {
        Argument name = Argument.required("name", Text.of("Group name", "Gruppenname"))
                .suggest(previous -> List.copyOf(config.snapshot().groups().keySet()));
        return Command.builder("group")
                .description(Text.of("Shows and changes groups (groups/*.yml).", "Zeigt und ändert Gruppen (groups/*.yml)."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Lists every group.", "Listet alle Gruppen auf."))
                        .executes(context -> listGroups(context.out(), config.snapshot())))
                .subcommand(Command.builder("info")
                        .description(Text.of("Shows one group.", "Zeigt eine Gruppe."))
                        .argument(name)
                        .executes(context -> showGroup(context.out(), config.snapshot(), context.argument("name"))))
                .subcommand(Command.builder("create")
                        .description(Text.of("Creates a group (java auto, heap 512M to 1G).",
                                "Legt eine Gruppe an (java auto, Heap 512M bis 1G)."))
                        .argument(Argument.required("name", Text.of("New group name", "Neuer Gruppenname")))
                        .argument(Argument.required("template", Text.of("Template folder in the home, for example Lobby-Server",
                                "Template-Ordner im Home, zum Beispiel Lobby-Server")).suggest(templates()))
                        .argument(Argument.required("software", Text.of("paper, velocity, fabric or pandaspigot",
                                "paper, velocity, fabric oder pandaspigot")).suggest(Values.names(ServerSoftware.class)))
                        .argument(Argument.required("minecraft-version", Text.of("For example 26.3", "Zum Beispiel 26.3")))
                        .executes(context -> {
                            String group = context.argument("name");
                            ServerSoftware software = Values.enumValue(groupFile(group), "software",
                                    context.argument("software"), ServerSoftware.class);
                            GroupConfig value = new GroupConfig(GroupConfig.SCHEMA_VERSION, context.argument("template"),
                                    software, context.argument("minecraft-version"), JavaSelection.AUTO,
                                    new HeapConfig(DEFAULT_HEAP_MIN, DEFAULT_HEAP_MAX), List.of(),
                                    software == ServerSoftware.PANDASPIGOT ? CombatSystem.LEGACY : CombatSystem.MODERN);
                            ConfigChanged change = config.apply(List.of(requireNewGroup(group), new PutGroup(group, value)));
                            BuiltinCommands.report(context.out(), change, CloudHome.groupFileName(group));
                        }))
                .subcommand(Command.builder("set")
                        .description(Text.of("Changes one field of a group.", "Ändert ein Feld einer Gruppe."))
                        .argument(name)
                        .argument(Argument.required("field", Text.of(String.join(", ", GROUP_FIELDS),
                                String.join(", ", GROUP_FIELDS))).suggest(GROUP_FIELDS))
                        .argument(Argument.required("value", Text.of("New value", "Neuer Wert")))
                        .executes(context -> {
                            String group = context.argument("name");
                            String field = context.argument("field");
                            String value = context.argument("value");
                            ConfigOperation operation = snapshot -> snapshot.withGroup(group,
                                    changeGroup(group, existingGroup(snapshot, group), field, value));
                            ConfigChanged change = config.apply(operation);
                            BuiltinCommands.report(context.out(), change, CloudHome.groupFileName(group) + ": " + field + " = " + value);
                        }))
                .subcommand(Command.builder("delete")
                        .aliases("remove")
                        .description(Text.of("Deletes a group after asking; no task may use it.",
                                "Löscht eine Gruppe nach Rückfrage; kein Task darf sie nutzen."))
                        .argument(name)
                        .argument(confirmation())
                        .executes(context -> {
                            String group = context.argument("name");
                            RemoveGroup removal = new RemoveGroup(group);
                            if (!confirmDelete(context, config.snapshot(), removal, CloudHome.groupFileName(group))) {
                                return;
                            }
                            ConfigChanged change = config.apply(removal);
                            reportDeleted(context.out(), change, CloudHome.groupFileName(group));
                        }))
                .subcommand(Command.builder("wanted")
                        .description(Text.of("Switches the server kind of the group on or off (cloud.yml servers:).",
                                "Schaltet die Serverart der Gruppe ein oder aus (cloud.yml servers:)."))
                        .argument(name)
                        .argument(Argument.required("state", Text.of("on or off", "on oder off")).suggest(ON_OFF))
                        .executes(context -> {
                            String group = context.argument("name");
                            ServerKind kind = kindOf(config.snapshot(), group);
                            boolean wanted = Values.onOff(groupFile(group), "wanted", context.argument("state"));
                            ConfigChanged change = config.apply(new SetServerWanted(kind, wanted));
                            BuiltinCommands.report(context.out(), change, "servers." + kind.key() + " = " + wanted);
                        }))
                .build();
    }

    static Command task(ConfigService config) {
        Argument name = Argument.required("name", Text.of("Task name", "Taskname"))
                .suggest(previous -> List.copyOf(config.snapshot().tasks().keySet()));
        return Command.builder("task")
                .description(Text.of("Shows and changes tasks (tasks/*.yml).", "Zeigt und ändert Tasks (tasks/*.yml)."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Lists every task.", "Listet alle Tasks auf."))
                        .executes(context -> listTasks(context.out(), config.snapshot())))
                .subcommand(Command.builder("info")
                        .description(Text.of("Shows one task.", "Zeigt einen Task."))
                        .argument(name)
                        .executes(context -> showTask(context.out(), config.snapshot(), context.argument("name"))))
                .subcommand(Command.builder("create")
                        .description(Text.of("Creates a task with one service, on any node.",
                                "Legt einen Task mit einem Dienst an, auf beliebigem Node."))
                        .argument(Argument.required("name", Text.of("New task name", "Neuer Taskname")))
                        .argument(Argument.required("group", Text.of("Group it runs", "Gruppe, die er startet"))
                                .suggest(previous -> List.copyOf(config.snapshot().groups().keySet())))
                        .argument(Argument.optional("mode", Text.of(
                                "static (default: one static service) or dynamic (one dynamic service)",
                                "static (Standard: ein statischer Dienst) oder dynamic (ein dynamischer Dienst)"))
                                .suggest(Values.names(TaskMode.class)))
                        .executes(context -> {
                            String task = context.argument("name");
                            TaskMode mode = context.optional("mode").isEmpty() ? TaskMode.STATIC
                                    : Values.enumValue(taskFile(task), "mode", context.argument("mode"), TaskMode.class);
                            ScalingConfig scaling = mode == TaskMode.STATIC ? ScalingConfig.single()
                                    : ScalingConfig.counts(0, 1, 1);
                            TaskConfig value = new TaskConfig(TaskConfig.SCHEMA_VERSION, context.argument("group"),
                                    scaling, List.of());
                            ConfigChanged change = config.apply(List.of(requireNewTask(task), new PutTask(task, value)));
                            BuiltinCommands.report(context.out(), change, CloudHome.taskFileName(task));
                        }))
                .subcommand(Command.builder("set")
                        .description(Text.of("Changes one field of a task.", "Ändert ein Feld eines Tasks."))
                        .argument(name)
                        .argument(Argument.required("field", Text.of(String.join(", ", TASK_FIELDS),
                                String.join(", ", TASK_FIELDS))).suggest(TASK_FIELDS))
                        .argument(Argument.required("value", Text.of("New value; nodes: comma list, \"\" for any node",
                                "Neuer Wert; nodes: Liste mit Kommas, \"\" für beliebigen Node")))
                        .executes(context -> {
                            String task = context.argument("name");
                            String field = context.argument("field");
                            String value = context.argument("value");
                            ConfigOperation operation = snapshot -> snapshot.withTask(task,
                                    changeTask(task, existingTask(snapshot, task), field, value));
                            ConfigChanged change = config.apply(operation);
                            BuiltinCommands.report(context.out(), change, CloudHome.taskFileName(task) + ": " + field + " = " + value);
                        }))
                .subcommand(Command.builder("delete")
                        .aliases("remove")
                        .description(Text.of("Deletes a task after asking.", "Löscht einen Task nach Rückfrage."))
                        .argument(name)
                        .argument(confirmation())
                        .executes(context -> {
                            String task = context.argument("name");
                            RemoveTask removal = new RemoveTask(task);
                            if (!confirmDelete(context, config.snapshot(), removal, CloudHome.taskFileName(task))) {
                                return;
                            }
                            ConfigChanged change = config.apply(removal);
                            reportDeleted(context.out(), change, CloudHome.taskFileName(task));
                        }))
                .subcommand(Command.builder("wanted")
                        .description(Text.of("Switches the server kind of the task's group on or off.",
                                "Schaltet die Serverart der Gruppe des Tasks ein oder aus."))
                        .argument(name)
                        .argument(Argument.required("state", Text.of("on or off", "on oder off")).suggest(ON_OFF))
                        .executes(context -> {
                            String task = context.argument("name");
                            ConfigSnapshot snapshot = config.snapshot();
                            ServerKind kind = kindOf(snapshot, existingTask(snapshot, task).group());
                            boolean wanted = Values.onOff(taskFile(task), "wanted", context.argument("state"));
                            ConfigChanged change = config.apply(new SetServerWanted(kind, wanted));
                            BuiltinCommands.report(context.out(), change, "servers." + kind.key() + " = " + wanted);
                        }))
                .build();
    }

    // ---- deleting -------------------------------------------------------------------------------

    /** The optional {@code --yes} after the name of {@code group delete} and {@code task delete}. */
    private static Argument confirmation() {
        return Argument.optional("confirm", Text.of("--yes or -y deletes without asking (for scripts)",
                "--yes oder -y löscht ohne Rückfrage (für Skripte)")).suggest(YES_FLAGS);
    }

    /**
     * Whether a deletion may go ahead. Refuses anything but {@code --yes} or {@code -y} after the
     * name. Then checks the deletion against the current configuration with the
     * same rules {@link ConfigService#apply} uses, so nobody confirms a deletion that would fail
     * anyway (unknown name, group still used by a task). Then, unless {@code --yes} or {@code -y}
     * was given, asks; Enter means no. A cancelled question ends the command as cancelled.
     */
    static boolean confirmDelete(CommandContext context, ConfigSnapshot snapshot, ConfigOperation removal, String file)
            throws ConfigException, CommandException, PromptCancelledException {
        Optional<String> flag = context.optional("confirm");
        if (flag.isPresent() && !YES_FLAGS.contains(flag.get())) {
            throw new CommandException(Text.of("Only --yes or -y may follow the name.",
                    "Nach dem Namen ist nur --yes oder -y erlaubt."));
        }
        List<ConfigError> errors = ConfigValidator.validate(removal.apply(snapshot));
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        if (flag.isPresent()) {
            return true;
        }
        boolean delete = context.prompter().ask(Question.yesNo(Text.of("Really delete " + file + "?",
                file + " wirklich löschen?")).defaultValue(false));
        if (!delete) {
            context.out().println(Text.of("Nothing was deleted.", "Es wurde nichts gelöscht."));
        }
        return delete;
    }

    private static void reportDeleted(ConsoleOutput out, ConfigChanged change, String file) {
        if (change.isEmpty()) {
            out.println(Text.of("Unchanged: %1$s", "Unverändert: %1$s"), file);
        } else {
            out.success(Text.of("Deleted: %1$s", "Gelöscht: %1$s"), file);
        }
    }

    // ---- changes --------------------------------------------------------------------------------

    static GroupConfig changeGroup(String name, GroupConfig g, String field, String value) throws ConfigException {
        String file = groupFile(name);
        return switch (field) {
            case "template" -> g.withTemplate(value);
            case "software" -> g.withSoftware(Values.enumValue(file, field, value, ServerSoftware.class));
            case "minecraft-version" -> g.withMinecraftVersion(value);
            case "java" -> g.withJava(parse(file, field, JavaSelection.FORMAT, () -> JavaSelection.parse(value)));
            case "heap.min" -> g.withHeap(new HeapConfig(parse(file, field, MemorySize.FORMAT, () -> MemorySize.parse(value)), heap(g).max()));
            case "heap.max" -> g.withHeap(new HeapConfig(heap(g).min(), parse(file, field, MemorySize.FORMAT, () -> MemorySize.parse(value))));
            case "combat" -> g.withCombat(Values.enumValue(file, field, value, CombatSystem.class));
            case "drain-timeout" -> g.withDrainTimeout(DEFAULT_VALUE.equals(value) ? null
                    : parse(file, field, TimeSpan.FORMAT + ", or " + DEFAULT_VALUE, () -> TimeSpan.parse(value)));
            default -> throw unknownField(file, GROUP_FIELDS);
        };
    }

    static TaskConfig changeTask(String name, TaskConfig t, String field, String value) throws ConfigException {
        String file = taskFile(name);
        ScalingConfig s = t.scaling() == null ? ScalingConfig.single() : t.scaling();
        return switch (field) {
            case "group" -> t.withGroup(value);
            // Shorthand: static makes min-services (at least 1) static services and nothing above
            // (static = min = max), like the migration of a version-1 file; dynamic makes none static.
            case "mode" -> t.withScaling(Values.enumValue(file, field, value, TaskMode.class) == TaskMode.STATIC
                    ? staticCounts(s)
                    : s.withStaticServices(0));
            case "static-services" -> t.withScaling(s.withStaticServices(Values.integer(file, "scaling." + field, value)));
            case "min-services" -> t.withScaling(s.withMinServices(Values.integer(file, "scaling." + field, value)));
            case "max-services" -> t.withScaling(s.withMaxServices(Values.integer(file, "scaling." + field, value)));
            case "players-per-service" -> t.withScaling(s.withPlayersPerService(Values.integer(file, "scaling." + field, value)));
            case "scale-up-at" -> t.withScaling(s.withScaleUpAt(Values.integer(file, "scaling." + field, value)));
            case "scale-down-below" -> t.withScaling(s.withScaleDownBelow(Values.integer(file, "scaling." + field, value)));
            case "scale-down-after" -> t.withScaling(s.withScaleDownAfter(
                    parse(file, "scaling." + field, TimeSpan.FORMAT, () -> TimeSpan.parse(value))));
            case "cooldown" -> t.withScaling(s.withCooldown(
                    parse(file, "scaling." + field, TimeSpan.FORMAT, () -> TimeSpan.parse(value))));
            case "nodes" -> t.withNodes(nodes(value));
            default -> throw unknownField(file, TASK_FIELDS);
        };
    }

    /** {@code mode static}: static = min = max = {@code min-services}, at least 1. */
    static ScalingConfig staticCounts(ScalingConfig s) {
        int count = Math.max(1, s.minServices());
        return s.withCounts(count, count, count);
    }

    private static List<String> nodes(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",", -1)).map(String::strip).toList();
    }

    private static HeapConfig heap(GroupConfig g) {
        return g.heap() == null ? new HeapConfig(DEFAULT_HEAP_MIN, DEFAULT_HEAP_MAX) : g.heap();
    }

    /** Runs a model parser that throws IllegalArgumentException and turns that into invalid-value. */
    private static <T> T parse(String file, String path, String format, java.util.function.Supplier<T> parser) throws ConfigException {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw Values.invalid(file, path, format);
        }
    }

    private static ConfigException unknownField(String file, List<String> fields) {
        return new ConfigException(ConfigError.of(file, "", ErrorCode.INVALID_VALUE, String.join(", ", fields)));
    }

    private static ConfigOperation requireNewGroup(String name) {
        return snapshot -> {
            if (snapshot.groups().containsKey(name)) {
                throw new ConfigException(ConfigError.of(groupFile(name), "", ErrorCode.ALREADY_EXISTS, name));
            }
            return snapshot;
        };
    }

    private static ConfigOperation requireNewTask(String name) {
        return snapshot -> {
            if (snapshot.tasks().containsKey(name)) {
                throw new ConfigException(ConfigError.of(taskFile(name), "", ErrorCode.ALREADY_EXISTS, name));
            }
            return snapshot;
        };
    }

    static GroupConfig existingGroup(ConfigSnapshot snapshot, String name) throws ConfigException {
        GroupConfig group = snapshot.groups().get(name);
        if (group == null) {
            throw new ConfigException(ConfigError.of(CloudHome.GROUPS_DIR + "/", "", ErrorCode.UNKNOWN_GROUP,
                    ConfigNames.safeArgument(name)));
        }
        return group;
    }

    static TaskConfig existingTask(ConfigSnapshot snapshot, String name) throws ConfigException {
        TaskConfig task = snapshot.tasks().get(name);
        if (task == null) {
            throw new ConfigException(ConfigError.of(CloudHome.TASKS_DIR + "/", "", ErrorCode.UNKNOWN_TASK,
                    ConfigNames.safeArgument(name)));
        }
        return task;
    }

    static ServerKind kindOf(ConfigSnapshot snapshot, String group) throws ConfigException, CommandException {
        GroupConfig config = existingGroup(snapshot, group);
        for (ServerKind kind : ServerKind.values()) {
            if (kind.templateFolder().equals(config.template())) {
                return kind;
            }
        }
        throw new CommandException(Text.of(
                "The template of this group is none of the server kinds in cloud.yml (servers:), so it has no wanted switch.",
                "Das Template dieser Gruppe gehört zu keiner Serverart in cloud.yml (servers:), daher gibt es keinen Wanted-Schalter."));
    }

    private static String groupFile(String name) {
        return ConfigNames.isValidName(name) ? CloudHome.groupFileName(name) : CloudHome.GROUPS_DIR + "/";
    }

    private static String taskFile(String name) {
        return ConfigNames.isValidName(name) ? CloudHome.taskFileName(name) : CloudHome.TASKS_DIR + "/";
    }

    private static List<String> templates() {
        List<String> folders = new ArrayList<>();
        for (ServerKind kind : ServerKind.values()) {
            folders.add(kind.templateFolder());
        }
        return folders;
    }

    // ---- output ---------------------------------------------------------------------------------

    private static void listGroups(ConsoleOutput out, ConfigSnapshot snapshot) {
        if (snapshot.groups().isEmpty()) {
            out.println(Text.of("No groups yet. \"group create\" adds one.", "Noch keine Gruppen. „group create“ legt eine an."));
            return;
        }
        int width = snapshot.groups().keySet().stream().mapToInt(String::length).max().orElse(0);
        snapshot.groups().forEach((name, g) -> out.println(BuiltinCommands.pad(name, width) + "  " + g.template() + "  "
                + Values.name(g.software()) + " " + g.minecraftVersion() + "  " + wantedLabel(snapshot, g, out)));
    }

    private static void showGroup(ConsoleOutput out, ConfigSnapshot snapshot, String name) throws ConfigException {
        GroupConfig g = existingGroup(snapshot, name);
        out.heading(CloudHome.groupFileName(name));
        out.println("  template          = " + g.template());
        out.println("  software          = " + Values.name(g.software()));
        out.println("  minecraft-version = " + g.minecraftVersion());
        out.println("  java              = " + g.java());
        out.println("  heap.min          = " + heap(g).min());
        out.println("  heap.max          = " + heap(g).max());
        out.println("  combat            = " + (g.combat() == null ? "" : Values.name(g.combat())));
        out.println("  drain-timeout     = " + g.drainTimeoutOrDefault() + (g.drainTimeout() == null ? " (default)" : ""));
        out.println("  catalog           = " + String.join(", ", g.catalog().stream().map(c -> c.id()).toList()));
        out.println("  wanted            = " + wantedLabel(snapshot, g, out));
    }

    private static void listTasks(ConsoleOutput out, ConfigSnapshot snapshot) {
        if (snapshot.tasks().isEmpty()) {
            out.println(Text.of("No tasks yet. \"task create\" adds one.", "Noch keine Tasks. „task create“ legt einen an."));
            return;
        }
        int width = snapshot.tasks().keySet().stream().mapToInt(String::length).max().orElse(0);
        snapshot.tasks().forEach((name, t) -> {
            ScalingConfig s = t.scaling() == null ? ScalingConfig.single() : t.scaling();
            out.println(BuiltinCommands.pad(name, width) + "  group " + t.group() + "  " + modeLabel(s)
                    + "  " + s.minServices() + ".." + s.maxServices());
        });
    }

    /** {@code static}, {@code dynamic} or {@code static N + dynamic} for the task list. */
    static String modeLabel(ScalingConfig s) {
        if (!s.allowsDynamic()) {
            return "static";
        }
        return s.staticServices() == 0 ? "dynamic" : "static " + s.staticServices() + " + dynamic";
    }

    private static void showTask(ConsoleOutput out, ConfigSnapshot snapshot, String name) throws ConfigException {
        TaskConfig t = existingTask(snapshot, name);
        ScalingConfig s = t.scaling() == null ? ScalingConfig.single() : t.scaling();
        out.heading(CloudHome.taskFileName(name));
        out.println("  group               = " + t.group());
        out.println("  mode                = " + modeLabel(s));
        out.println("  static-services     = " + s.staticServices());
        out.println("  min-services        = " + s.minServices());
        out.println("  max-services        = " + s.maxServices());
        out.println("  players-per-service = " + s.playersPerService());
        out.println("  scale-up-at         = " + s.scaleUpAt());
        out.println("  scale-down-below    = " + s.scaleDownBelow());
        out.println("  scale-down-after    = " + s.scaleDownAfter());
        out.println("  cooldown            = " + s.cooldown());
        out.println("  nodes               = " + (t.nodes().isEmpty() ? "*" : String.join(",", t.nodes())));
        Optional<GroupConfig> group = Optional.ofNullable(snapshot.groups().get(t.group()));
        group.ifPresent(g -> out.println("  wanted              = " + wantedLabel(snapshot, g, out)));
    }

    private static String wantedLabel(ConfigSnapshot snapshot, GroupConfig group, ConsoleOutput out) {
        for (ServerKind kind : ServerKind.values()) {
            if (kind.templateFolder().equals(group.template())) {
                boolean wanted = snapshot.cloud().servers() != null && snapshot.cloud().servers().isWanted(kind);
                return (wanted ? Text.of("wanted", "gewünscht") : Text.of("not wanted", "nicht gewünscht")).get(out.language());
            }
        }
        return "-";
    }
}
