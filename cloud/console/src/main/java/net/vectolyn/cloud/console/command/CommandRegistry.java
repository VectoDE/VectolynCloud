package net.vectolyn.cloud.console.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * All console commands, by name and alias. Other modules add theirs here: cloud-node registers the
 * service commands ({@code start}, {@code stop}, {@code screen}, ...) once it is running.
 *
 * <p>Thread-safe. A name or alias can belong to one command only.
 */
public final class CommandRegistry {

    private final Map<String, Command> byWord = new HashMap<>();
    private final Map<String, Command> byName = new TreeMap<>();

    /**
     * Adds a command.
     *
     * @throws IllegalArgumentException if its name or an alias is taken
     */
    public synchronized void register(Command command) {
        Objects.requireNonNull(command, "command");
        for (String word : command.names()) {
            if (byWord.containsKey(word)) {
                throw new IllegalArgumentException("command name already registered: " + word);
            }
        }
        for (String word : command.names()) {
            byWord.put(word, command);
        }
        byName.put(command.name(), command);
    }

    /**
     * Removes the command with this name (not an alias).
     *
     * @return whether it was registered
     */
    public synchronized boolean unregister(String name) {
        Command command = byName.remove(name);
        if (command == null) {
            return false;
        }
        command.names().forEach(byWord::remove);
        return true;
    }

    /** The command with this name or alias. */
    public synchronized Optional<Command> find(String word) {
        return Optional.ofNullable(byWord.get(word));
    }

    /** Every command, sorted by name. */
    public synchronized List<Command> commands() {
        return List.copyOf(byName.values());
    }

    /**
     * Whether a typed line belongs to a {@link Command#sensitive() sensitive} command or subcommand.
     * Used to keep such lines out of the history. Reads the line leniently, so a line with a
     * syntax error is judged by the words that can be read.
     */
    public boolean isSensitive(String line) {
        List<String> words = CommandLineParser.partial(line).words();
        if (words.isEmpty()) {
            return false;
        }
        Optional<Command> current = find(words.getFirst());
        int index = 1;
        while (current.isPresent()) {
            Command command = current.get();
            if (command.sensitive()) {
                return true;
            }
            if (index >= words.size()) {
                return false;
            }
            current = command.subcommand(words.get(index++));
        }
        return false;
    }
}
