package net.vectolyn.cloud.console.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectolyn.cloud.console.Text;

/**
 * A console command or subcommand: name, aliases, description in English and German, and either
 * subcommands or an executor with its arguments. Immutable; build one with {@link #builder(String)}.
 *
 * <pre>{@code
 * Command.builder("service")
 *     .description(Text.of("Manage services.", "Dienste verwalten."))
 *     .subcommand(Command.builder("stop")
 *         .description(Text.of("Stop a service.", "Einen Dienst stoppen."))
 *         .argument(Argument.required("service", Text.of("Service id", "Dienst-ID")))
 *         .executes(context -> services.stop(context.argument("service"))))
 *     .build();
 * }</pre>
 *
 * <p>A command with subcommands but without executor prints its usage when called without one.
 * Lines of a {@link Builder#sensitive() sensitive} command are never written to the history.
 */
public final class Command {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private final String name;
    private final List<String> aliases;
    private final Text description;
    private final List<Argument> arguments;
    private final List<Command> subcommands;
    private final CommandExecutor executor;
    private final boolean sensitive;

    private Command(Builder builder) {
        this.name = builder.name;
        this.aliases = List.copyOf(builder.aliases);
        this.description = Objects.requireNonNull(builder.description, "description of " + builder.name);
        this.arguments = List.copyOf(builder.arguments);
        this.subcommands = List.copyOf(builder.subcommands);
        this.executor = builder.executor;
        this.sensitive = builder.sensitive;
        if (executor == null && subcommands.isEmpty()) {
            throw new IllegalArgumentException(name + ": a command needs an executor or subcommands");
        }
        if (!subcommands.isEmpty() && !arguments.isEmpty()) {
            throw new IllegalArgumentException(name + ": arguments belong to the subcommands");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Command sub : subcommands) {
            for (String word : sub.names()) {
                if (!seen.add(word)) {
                    throw new IllegalArgumentException(name + ": subcommand name used twice: " + word);
                }
            }
        }
        for (int i = 0; i < arguments.size(); i++) {
            Argument argument = arguments.get(i);
            boolean last = i == arguments.size() - 1;
            if (argument.rest() && !last) {
                throw new IllegalArgumentException(name + ": only the last argument can take the rest");
            }
            if (!argument.optional() && i > 0 && arguments.get(i - 1).optional()) {
                throw new IllegalArgumentException(name + ": required argument after an optional one");
            }
        }
    }

    /** Starts a command named {@code name} (lower case letters, digits, {@code -}). */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** The name. */
    public String name() {
        return name;
    }

    /** Other names for it. */
    public List<String> aliases() {
        return aliases;
    }

    /** Name and aliases. */
    public List<String> names() {
        List<String> all = new ArrayList<>(aliases.size() + 1);
        all.add(name);
        all.addAll(aliases);
        return all;
    }

    /** One line about what it does. */
    public Text description() {
        return description;
    }

    /** Its arguments, in order; empty for a command with subcommands. */
    public List<Argument> arguments() {
        return arguments;
    }

    /** Its subcommands. */
    public List<Command> subcommands() {
        return subcommands;
    }

    /** What runs it; empty for a pure group of subcommands. */
    public Optional<CommandExecutor> executor() {
        return Optional.ofNullable(executor);
    }

    /** Whether its lines must never be stored in the history. */
    public boolean sensitive() {
        return sensitive;
    }

    /** The subcommand with this name or alias. */
    public Optional<Command> subcommand(String word) {
        for (Command sub : subcommands) {
            if (sub.names().contains(word)) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }

    /** The argument part of the usage line, for example {@code <key> <value>}. */
    public String argumentUsage() {
        if (!subcommands.isEmpty()) {
            List<String> names = new ArrayList<>();
            subcommands.forEach(sub -> names.add(sub.name()));
            return "<" + String.join("|", names) + ">";
        }
        List<String> parts = new ArrayList<>();
        arguments.forEach(argument -> parts.add(argument.usage()));
        return String.join(" ", parts);
    }

    @Override
    public String toString() {
        return "Command[" + name + "]";
    }

    /** Builds a {@link Command}. */
    public static final class Builder {

        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private Text description;
        private final List<Argument> arguments = new ArrayList<>();
        private final List<Command> subcommands = new ArrayList<>();
        private CommandExecutor executor;
        private boolean sensitive;

        private Builder(String name) {
            this.name = checkName(name);
        }

        /** Other names. */
        public Builder aliases(String... names) {
            for (String alias : names) {
                aliases.add(checkName(alias));
            }
            return this;
        }

        /** One line about what it does, in English and German. */
        public Builder description(Text text) {
            this.description = Objects.requireNonNull(text, "text");
            return this;
        }

        /** Adds a positional argument. */
        public Builder argument(Argument argument) {
            arguments.add(Objects.requireNonNull(argument, "argument"));
            return this;
        }

        /** Adds a subcommand. */
        public Builder subcommand(Command command) {
            subcommands.add(Objects.requireNonNull(command, "command"));
            return this;
        }

        /** Adds a subcommand from its builder. */
        public Builder subcommand(Builder command) {
            return subcommand(command.build());
        }

        /** What runs the command. */
        public Builder executes(CommandExecutor value) {
            this.executor = Objects.requireNonNull(value, "executor");
            return this;
        }

        /** Never store lines of this command in the history (for commands that take secrets). */
        public Builder sensitive() {
            this.sensitive = true;
            return this;
        }

        /** The command. */
        public Command build() {
            return new Command(this);
        }

        private static String checkName(String value) {
            Objects.requireNonNull(value, "name");
            if (!NAME.matcher(value).matches()) {
                throw new IllegalArgumentException("command names are lower case letters, digits and -: " + value);
            }
            return value;
        }
    }
}
