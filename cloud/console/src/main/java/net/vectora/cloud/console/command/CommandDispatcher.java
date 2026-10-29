package net.vectora.cloud.console.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.Messages;
import net.vectora.cloud.console.prompt.PromptCancelledException;
import net.vectora.cloud.console.prompt.Prompter;

/**
 * Parses a typed line, finds the command and subcommand, binds the arguments and runs it. Errors
 * are shown in the configured language; they name the rule that was broken, never the text typed.
 */
public final class CommandDispatcher {

    private static final System.Logger LOG = System.getLogger(CommandDispatcher.class.getName());

    /** What happened to a line. */
    public enum Outcome {
        /** Nothing typed. */
        EMPTY,
        /** The command ran. */
        DONE,
        /** The command ran and reported an error. */
        FAILED,
        /** A question of the command was cancelled. */
        CANCELLED,
        /** No such command. */
        UNKNOWN_COMMAND,
        /** Wrong subcommand or arguments. */
        USAGE_ERROR,
        /** The line could not be split into words. */
        SYNTAX_ERROR
    }

    private final CommandRegistry registry;
    private final ConsoleOutput out;
    private final Prompter prompter;

    /** Dispatcher for {@code registry}, writing to {@code out}. */
    public CommandDispatcher(CommandRegistry registry, ConsoleOutput out, Prompter prompter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.out = Objects.requireNonNull(out, "out");
        this.prompter = Objects.requireNonNull(prompter, "prompter");
    }

    /** Runs one typed line. Never throws for bad input or a failing command. */
    public Outcome execute(String line) {
        List<String> words;
        try {
            words = CommandLineParser.tokenize(line);
        } catch (CommandSyntaxException e) {
            out.error(e.message(out.language()));
            return Outcome.SYNTAX_ERROR;
        }
        if (words.isEmpty()) {
            return Outcome.EMPTY;
        }
        Optional<Command> found = registry.find(words.getFirst());
        if (found.isEmpty()) {
            out.error(Messages.UNKNOWN_COMMAND);
            return Outcome.UNKNOWN_COMMAND;
        }
        Command command = found.get();
        List<String> path = new ArrayList<>(List.of(words.getFirst()));
        int index = 1;
        while (!command.subcommands().isEmpty()) {
            if (index >= words.size()) {
                if (command.executor().isPresent()) {
                    break;
                }
                out.error(Messages.MISSING_SUBCOMMAND, usage(path, command));
                return Outcome.USAGE_ERROR;
            }
            Optional<Command> sub = command.subcommand(words.get(index));
            if (sub.isEmpty()) {
                out.error(Messages.UNKNOWN_SUBCOMMAND, usage(path, command));
                return Outcome.USAGE_ERROR;
            }
            command = sub.get();
            path.add(words.get(index));
            index++;
        }
        List<String> given = words.subList(index, words.size());
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<Argument> arguments = command.arguments();
        int position = 0;
        for (Argument argument : arguments) {
            if (argument.rest()) {
                List<String> rest = given.subList(Math.min(position, given.size()), given.size());
                if (rest.isEmpty() && !argument.optional()) {
                    out.error(Messages.MISSING_ARGUMENT, argument.name(), usage(path, command));
                    return Outcome.USAGE_ERROR;
                }
                values.put(argument.name(), List.copyOf(rest));
                position = given.size();
            } else if (position < given.size()) {
                values.put(argument.name(), List.of(given.get(position++)));
            } else if (!argument.optional()) {
                out.error(Messages.MISSING_ARGUMENT, argument.name(), usage(path, command));
                return Outcome.USAGE_ERROR;
            }
        }
        if (position < given.size()) {
            out.error(Messages.TOO_MANY_ARGUMENTS, usage(path, command));
            return Outcome.USAGE_ERROR;
        }
        CommandExecutor executor = command.executor().orElseThrow();
        return run(executor, new CommandContext(path, values, out, prompter, registry), path);
    }

    private Outcome run(CommandExecutor executor, CommandContext context, List<String> path) {
        try {
            executor.execute(context);
            return Outcome.DONE;
        } catch (CommandException e) {
            out.error(e.message(out.language()));
        } catch (ConfigException e) {
            out.error(e.describe(out.language()));
        } catch (PromptCancelledException e) {
            out.warning(Messages.CANCELLED);
            return Outcome.CANCELLED;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Command " + String.join(" ", path) + " failed", e);
            out.error(Messages.FILE_ERROR, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.warning(Messages.CANCELLED);
            return Outcome.CANCELLED;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Command " + String.join(" ", path) + " failed", e);
            out.error(Messages.INTERNAL_ERROR);
        }
        return Outcome.FAILED;
    }

    /** The usage line of a command: its path followed by {@link Command#argumentUsage()}. */
    public static String usage(List<String> path, Command command) {
        String arguments = command.argumentUsage();
        String head = String.join(" ", path);
        return arguments.isEmpty() ? head : head + " " + arguments;
    }

    /**
     * Completion candidates for the line up to the cursor: command names, subcommand names, or the
     * suggestions of the argument at the cursor. Sorted, only those starting with the word typed.
     */
    public static List<String> complete(CommandRegistry registry, String lineUpToCursor) {
        CommandLineParser.Partial partial = CommandLineParser.partial(lineUpToCursor);
        List<String> words = partial.words();
        List<String> done = partial.openWord() ? words.subList(0, words.size() - 1) : words;
        String prefix = partial.openWord() ? words.getLast() : "";
        List<String> candidates = new ArrayList<>();
        if (done.isEmpty()) {
            registry.commands().forEach(command -> candidates.addAll(command.names()));
        } else {
            Optional<Command> found = registry.find(done.getFirst());
            if (found.isEmpty()) {
                return List.of();
            }
            Command command = found.get();
            int index = 1;
            while (!command.subcommands().isEmpty() && index < done.size()) {
                Optional<Command> sub = command.subcommand(done.get(index));
                if (sub.isEmpty()) {
                    return List.of();
                }
                command = sub.get();
                index++;
            }
            if (!command.subcommands().isEmpty()) {
                command.subcommands().forEach(sub -> candidates.addAll(sub.names()));
            } else {
                int argumentIndex = done.size() - index;
                List<Argument> arguments = command.arguments();
                Argument argument = null;
                if (argumentIndex < arguments.size()) {
                    argument = arguments.get(argumentIndex);
                } else if (!arguments.isEmpty() && arguments.getLast().rest()) {
                    argument = arguments.getLast();
                }
                if (argument != null) {
                    candidates.addAll(argument.suggestions().suggest(List.copyOf(done)));
                }
            }
        }
        TreeSet<String> result = new TreeSet<>();
        for (String candidate : candidates) {
            if (candidate.startsWith(prefix)) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }
}
