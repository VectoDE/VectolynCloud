package net.vectolyn.cloud.backup;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Scripted service manager: states per service, answers to console commands, holds. */
public final class FakeServiceControl implements ServiceControl {

    /** What a running server answers to a command. */
    @FunctionalInterface
    public interface Server {
        /** The output line for {@code command}, or null for no answer. */
        String answer(String serviceId, String command) throws IOException;
    }

    /** Paper's answers. */
    public static final Server PAPER = (id, command) -> switch (command) {
        case "save-off" -> "[12:00:00 INFO]: Automatic saving is now disabled";
        case "save-all flush" -> "[12:00:01 INFO]: Saved the game";
        case "save-on" -> "[12:00:02 INFO]: Automatic saving is now enabled";
        default -> null;
    };

    /** PandaSpigot 1.8.8's answers. */
    public static final Server LEGACY = (id, command) -> switch (command) {
        case "save-off" -> "[12:00:00 INFO]: Turned off world auto-saving";
        case "save-all flush" -> "[12:00:01 INFO]: Saved the world";
        case "save-on" -> "[12:00:02 INFO]: Turned on world auto-saving";
        default -> null;
    };

    private final Map<String, State> states = new HashMap<>();
    private final List<String> commands = new ArrayList<>();
    private final Set<String> held = new HashSet<>();
    private Server server = PAPER;
    private Runnable onCommand = () -> { };

    /** Sets the state of a service. */
    public synchronized FakeServiceControl state(String id, State state) {
        states.put(id, state);
        return this;
    }

    /** Sets how running servers answer. */
    public synchronized FakeServiceControl server(Server value) {
        this.server = value;
        return this;
    }

    /** Runs before each command is answered (for example to start another operation). */
    public synchronized FakeServiceControl onCommand(Runnable action) {
        this.onCommand = action;
        return this;
    }

    /** Every command sent, as {@code id: command}. */
    public synchronized List<String> commands() {
        return List.copyOf(commands);
    }

    /** Services held stopped right now. */
    public synchronized Set<String> held() {
        return Set.copyOf(held);
    }

    @Override
    public synchronized State state(String serviceId) {
        return states.getOrDefault(serviceId, State.STOPPED);
    }

    @Override
    public boolean execute(String serviceId, String command, Predicate<String> confirmation, Duration timeout)
            throws IOException {
        Server current;
        Runnable action;
        synchronized (this) {
            if (state(serviceId) != State.RUNNING) {
                throw new IOException(serviceId + " is not running");
            }
            commands.add(serviceId + ": " + command);
            current = server;
            action = onCommand;
        }
        action.run();
        String line = current.answer(serviceId, command);
        return line != null && confirmation.test(line);
    }

    @Override
    public synchronized Hold holdStopped(String serviceId) throws BackupException {
        if (state(serviceId) != State.STOPPED) {
            throw new BackupException(BackupException.Reason.SERVICE_RUNNING, serviceId + " is not stopped");
        }
        held.add(serviceId);
        return () -> {
            synchronized (FakeServiceControl.this) {
                held.remove(serviceId);
            }
        };
    }
}
