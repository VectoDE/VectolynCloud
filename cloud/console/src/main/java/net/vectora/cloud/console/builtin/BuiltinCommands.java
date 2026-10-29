package net.vectora.cloud.console.builtin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.ConfigChanged;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.Messages;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.command.Argument;
import net.vectora.cloud.console.command.Command;
import net.vectora.cloud.console.command.CommandDispatcher;
import net.vectora.cloud.console.command.CommandException;
import net.vectora.cloud.console.command.CommandRegistry;
import net.vectora.cloud.console.terminal.ConsoleTerminal;

/**
 * The basic commands: {@code help}, {@code shutdown} (alias {@code exit}), {@code clear},
 * {@code config}, {@code group}, {@code task} and {@code node}. Service commands come from cloud-node.
 */
public final class BuiltinCommands {

    private BuiltinCommands() {
    }

    /**
     * Registers every basic command.
     *
     * @param shutdown stops the cloud; runs for {@code shutdown}, {@code exit}, Ctrl+C and Ctrl+D.
     *                 The console stops reading afterwards.
     */
    public static void register(Console console, ConfigService config, Runnable shutdown) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(shutdown, "shutdown");
        CommandRegistry registry = console.commands();
        registry.register(help(registry));
        registry.register(shutdown(() -> {
            shutdown.run();
            console.stop();
        }));
        registry.register(clear(console.terminal()));
        registry.register(config(config));
        registry.register(GroupCommands.group(config));
        registry.register(GroupCommands.task(config));
        registry.register(NodeCommands.node(config));
    }

    /** {@code help [command...]}. */
    public static Command help(CommandRegistry registry) {
        return Command.builder("help")
                .description(Text.of("Lists the commands, or explains one.", "Listet die Befehle auf oder erklärt einen."))
                .argument(Argument.optional("command", Text.of("Command and subcommand", "Befehl und Unterbefehl"))
                        .takingRest()
                        .suggest(previous -> registry.commands().stream().map(Command::name).toList()))
                .executes(context -> {
                    List<String> words = context.words("command");
                    ConsoleOutput out = context.out();
                    if (words.isEmpty()) {
                        out.heading(Messages.HELP_HEADING.get(out.language()));
                        int width = registry.commands().stream().mapToInt(c -> c.name().length()).max().orElse(0);
                        for (Command command : registry.commands()) {
                            out.println("  " + pad(command.name(), width) + "  " + command.description().get(out.language()));
                        }
                        out.println(Messages.HELP_FOOTER);
                        return;
                    }
                    Optional<Command> found = registry.find(words.getFirst());
                    if (found.isEmpty()) {
                        throw new CommandException(Messages.UNKNOWN_COMMAND);
                    }
                    Command command = found.get();
                    List<String> path = new java.util.ArrayList<>(List.of(command.name()));
                    for (String word : words.subList(1, words.size())) {
                        Optional<Command> sub = command.subcommand(word);
                        if (sub.isEmpty()) {
                            throw new CommandException(Messages.UNKNOWN_SUBCOMMAND, CommandDispatcher.usage(path, command));
                        }
                        command = sub.get();
                        path.add(command.name());
                    }
                    describe(out, path, command);
                })
                .build();
    }

    private static void describe(ConsoleOutput out, List<String> path, Command command) {
        out.heading(CommandDispatcher.usage(path, command));
        out.println("  " + command.description().get(out.language()));
        if (!command.aliases().isEmpty()) {
            out.println(Messages.HELP_ALIASES, String.join(", ", command.aliases()));
        }
        if (!command.subcommands().isEmpty()) {
            out.println(Messages.HELP_SUBCOMMANDS);
            int width = command.subcommands().stream().mapToInt(c -> (c.name() + " " + c.argumentUsage()).length()).max().orElse(0);
            for (Command sub : command.subcommands()) {
                out.println("  " + pad(sub.name() + " " + sub.argumentUsage(), width) + "  " + sub.description().get(out.language()));
            }
        }
        if (!command.arguments().isEmpty()) {
            out.println(Messages.HELP_ARGUMENTS);
            int width = command.arguments().stream().mapToInt(a -> a.usage().length()).max().orElse(0);
            for (Argument argument : command.arguments()) {
                out.println("  " + pad(argument.usage(), width) + "  " + argument.description().get(out.language()));
            }
        }
    }

    /** {@code shutdown}, alias {@code exit}. */
    public static Command shutdown(Runnable action) {
        return Command.builder(Console.SHUTDOWN_COMMAND)
                .aliases("exit")
                .description(Text.of("Stops every service and the cloud.", "Stoppt alle Dienste und die Cloud."))
                .executes(context -> {
                    context.out().warning(Text.of("Shutting down ...", "Fahre herunter ..."));
                    action.run();
                })
                .build();
    }

    /** {@code clear}. */
    public static Command clear(ConsoleTerminal terminal) {
        return Command.builder("clear")
                .aliases("cls")
                .description(Text.of("Clears the screen.", "Leert den Bildschirm."))
                .executes(context -> {
                    if (!terminal.ansi()) {
                        context.out().println(Text.of("This terminal cannot be cleared.", "Dieses Terminal lässt sich nicht leeren."));
                        return;
                    }
                    terminal.clearScreen();
                })
                .build();
    }

    /** {@code config get|set|list|reload}. */
    public static Command config(ConfigService config) {
        Argument key = Argument.required("key", Text.of("Field in cloud.yml, for example proxy.port",
                "Feld in cloud.yml, zum Beispiel proxy.port")).suggest(ConfigKeys.names());
        return Command.builder("config")
                .description(Text.of("Shows and changes cloud.yml.", "Zeigt und ändert cloud.yml."))
                .subcommand(Command.builder("get")
                        .description(Text.of("Shows one value.", "Zeigt einen Wert."))
                        .argument(key)
                        .executes(context -> {
                            ConfigKeys.Key k = key(context.argument("key"));
                            context.out().println(k.name() + " = " + k.getter().apply(config.snapshot().cloud()));
                        }))
                .subcommand(Command.builder("set")
                        .description(Text.of("Changes one value; it is checked like a hand-edited file.",
                                "Ändert einen Wert; er wird wie eine von Hand bearbeitete Datei geprüft."))
                        .argument(key)
                        .argument(Argument.required("value", Text.of("New value; \"\" clears an optional text",
                                "Neuer Wert; \"\" leert einen optionalen Text")).suggest(previous ->
                                previous.size() >= 3 ? ConfigKeys.find(previous.get(2)).map(ConfigKeys.Key::values).orElse(List.of()) : List.of()))
                        .executes(context -> {
                            ConfigKeys.Key k = key(context.argument("key"));
                            ConfigChanged change = config.apply(k.setter().operation(config.snapshot(), context.argument("value")));
                            report(context.out(), change, k.name() + " = " + k.getter().apply(config.snapshot().cloud()));
                        }))
                .subcommand(Command.builder("list")
                        .description(Text.of("Shows every value of cloud.yml.", "Zeigt alle Werte aus cloud.yml."))
                        .executes(context -> {
                            int width = ConfigKeys.names().stream().mapToInt(String::length).max().orElse(0);
                            for (ConfigKeys.Key k : ConfigKeys.all()) {
                                context.out().println(pad(k.name(), width) + " = " + k.getter().apply(config.snapshot().cloud()));
                            }
                        }))
                .subcommand(Command.builder("reload")
                        .description(Text.of("Reads every configuration file again.", "Liest alle Konfigurationsdateien neu ein."))
                        .executes(context -> {
                            Optional<ConfigChanged> change = config.reload();
                            if (change.isEmpty()) {
                                context.out().println(Text.of("No changes on disk.", "Keine Änderungen auf der Platte."));
                            } else {
                                context.out().success(Text.of("Reloaded: %1$s", "Neu geladen: %1$s"),
                                        String.join(", ", new java.util.TreeSet<>(change.get().files())));
                            }
                        }))
                .build();
    }

    private static ConfigKeys.Key key(String name) throws CommandException {
        return ConfigKeys.find(name).orElseThrow(() -> new CommandException(Text.of(
                "Unknown key. \"config list\" shows every key.", "Unbekannter Schlüssel. „config list“ zeigt alle Schlüssel.")));
    }

    /** Prints the outcome of a change: what is set now, or that nothing changed. */
    static void report(ConsoleOutput out, ConfigChanged change, String now) {
        if (change.isEmpty()) {
            out.println(Text.of("Unchanged: %1$s", "Unverändert: %1$s"), now);
        } else {
            out.success(Text.of("Saved: %1$s", "Gespeichert: %1$s"), now);
        }
    }

    static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
