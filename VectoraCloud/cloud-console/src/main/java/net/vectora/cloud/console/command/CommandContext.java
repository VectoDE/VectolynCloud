package net.vectora.cloud.console.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.prompt.Prompter;

/** What a running command gets: its arguments, the output, the question API and the registry. */
public final class CommandContext {

    private final List<String> path;
    private final Map<String, List<String>> values;
    private final ConsoleOutput out;
    private final Prompter prompter;
    private final CommandRegistry registry;

    CommandContext(List<String> path, Map<String, List<String>> values, ConsoleOutput out, Prompter prompter,
                   CommandRegistry registry) {
        this.path = List.copyOf(path);
        this.values = Map.copyOf(values);
        this.out = Objects.requireNonNull(out, "out");
        this.prompter = Objects.requireNonNull(prompter, "prompter");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** The command and subcommand names as typed, for example {@code [config, set]}. */
    public List<String> path() {
        return path;
    }

    /**
     * A required argument (for a {@link Argument#rest() rest} argument: the words joined by spaces).
     *
     * @throws IllegalArgumentException if the command has no such argument or it was left out
     */
    public String argument(String name) {
        return optional(name).orElseThrow(() -> new IllegalArgumentException("no value for argument " + name));
    }

    /** An optional argument. */
    public Optional<String> optional(String name) {
        List<String> words = values.get(name);
        return words == null || words.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", words));
    }

    /** All words of a {@link Argument#rest() rest} argument; empty if left out. */
    public List<String> words(String name) {
        return values.getOrDefault(name, List.of());
    }

    /** Where to write. */
    public ConsoleOutput out() {
        return out;
    }

    /** Asks questions (see {@link Prompter}). */
    public Prompter prompter() {
        return prompter;
    }

    /** The command registry, for example for {@code help}. */
    public CommandRegistry registry() {
        return registry;
    }

    /** The configured language. */
    public Language language() {
        return out.language();
    }
}
