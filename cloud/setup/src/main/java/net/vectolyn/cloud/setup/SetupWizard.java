package net.vectolyn.cloud.setup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.secret.SecretStore;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandException;

/**
 * The setup assistant. Three ways in, one way to write:
 *
 * <ul>
 *   <li>{@link #run}: the questions on the console (first start, and the {@code setup} command);</li>
 *   <li>{@link #nonInteractive}: the same answers from {@code --setup-file} for Docker, Pterodactyl
 *       and CI;</li>
 *   <li>{@link #ensureComplete}: what the node calls on every start; it picks one of both, or does
 *       nothing once {@code setup-complete} is set.</li>
 * </ul>
 *
 * All of them end in {@link SetupAnswers}, are checked by {@link SetupChecks} and written through
 * {@link ConfigService#apply}, the layer the console commands and hand-edited files use too.
 *
 * <pre>{@code
 * SetupEnvironment env = SetupEnvironment.of(settings, System.getenv());
 * SetupResult result = SetupWizard.ensureComplete(console, config, env);
 * if (!result.ready()) {
 *     return; // cancelled or EULA declined: start no server
 * }
 * SetupWizard.registerCommand(console, config, env);
 * }</pre>
 */
public final class SetupWizard {

    /** Name of the console command that runs the assistant again. */
    public static final String COMMAND = "setup";

    private SetupWizard() {
    }

    /** Whether the setup still has to run ({@code setup-complete} is false). */
    public static boolean needed(ConfigService config) {
        return !config.snapshot().cloud().setupComplete();
    }

    /**
     * Asks the questions on {@code console}, on the calling thread, and saves on request. Before the
     * first setup it creates the configuration; afterwards the current values are the defaults and
     * only differences are written.
     *
     * @throws IOException if saving fails; the setup then stays open
     */
    public static SetupResult run(Console console, ConfigService config, SetupEnvironment env) throws IOException {
        Objects.requireNonNull(console, "console");
        return new InteractiveSetup(config, new SecretStore(config.home()), env, console.terminal(), console.output(),
                groupList(env)).run();
    }

    /**
     * Takes the answers from the setup file in {@code env}, checks them like typed answers and saves
     * them. Writes nothing if anything is wrong. Once {@code setup-complete} is set it does not read
     * the file at all and returns {@link SetupResult.Status#ALREADY_COMPLETE}: a file that stays
     * mounted must never undo what was changed later with {@code setup} or by hand.
     *
     * @param out where to report the result
     * @throws SetupException if the file is missing, unreadable or has problems
     * @throws IOException    if saving fails
     */
    public static SetupResult nonInteractive(ConfigService config, SetupEnvironment env, ConsoleOutput out)
            throws SetupException, IOException {
        if (!needed(config)) {
            return SetupResult.of(SetupResult.Status.ALREADY_COMPLETE);
        }
        Path file = env.setupFile().orElseThrow(() -> new SetupException(java.util.List.of(SetupMessages.NEEDS_ANSWERS)));
        SetupFile parsed;
        try {
            parsed = SetupFile.read(file);
        } catch (SetupException e) {
            throw withHeading(file, e);
        }
        SetupAnswers defaults = SetupPlan.defaults(config.snapshot(), env);
        Path home = config.home().root();
        SetupResult result;
        try {
            SetupAnswers answers = parsed.toAnswers(defaults, name -> PasswordFile.read(home, name));
            result = SetupApplier.apply(config, new SecretStore(config.home()), answers, env);
        } catch (SetupException e) {
            throw withHeading(file, e);
        }
        if (out != null) {
            out.success(SetupMessages.FILE_APPLIED, file.getFileName());
            if (!result.files().isEmpty()) {
                out.success(SetupMessages.SAVED, String.join(", ", result.files()));
            }
            SecretStore secrets = new SecretStore(config.home());
            if (secrets.file().toFile().isFile()) {
                out.println(SecretAccess.describe(secrets.file(), env.windows(), groupList(env)));
            }
        }
        return result;
    }

    /**
     * What the node runs on every start before it starts any server:
     *
     * <ol>
     *   <li>{@code setup-complete} set: nothing ({@link SetupResult.Status#ALREADY_COMPLETE}); a setup
     *       file is left alone then, so a file that stays mounted cannot undo later changes;</li>
     *   <li>a setup file given: {@link #nonInteractive};</li>
     *   <li>questions allowed and a console there: {@link #run};</li>
     *   <li>otherwise a {@link SetupException} that says how to pass the answers.</li>
     * </ol>
     *
     * @param console the console, or null if there is none
     */
    public static SetupResult ensureComplete(Console console, ConfigService config, SetupEnvironment env)
            throws SetupException, IOException {
        if (!needed(config)) {
            return SetupResult.of(SetupResult.Status.ALREADY_COMPLETE);
        }
        if (env.setupFile().isPresent()) {
            return nonInteractive(config, env, console == null ? null : console.output());
        }
        if (env.interactive() && console != null) {
            return run(console, config, env);
        }
        throw new SetupException(java.util.List.of(SetupMessages.NEEDS_ANSWERS));
    }

    /** Registers {@code setup}, which runs the questions again with the current values as defaults. */
    public static void registerCommand(Console console, ConfigService config, SetupEnvironment env) {
        registerCommand(console, config, env, CommandHooks.NONE);
    }

    /**
     * Like {@link #registerCommand(Console, ConfigService, SetupEnvironment)}, and tells
     * {@code hooks} when a run starts and ends. A run saves in several steps and rolls them back if
     * a later one fails, so a caller that reacts to configuration changes (the node stops what is no
     * longer wanted) waits for {@link CommandHooks#finished()} and then compares the configuration
     * with the one at {@link CommandHooks#started()}.
     *
     * @param console the console to register in
     * @param config  the configuration
     * @param env     the setup environment
     * @param hooks   told about every run
     */
    public static void registerCommand(Console console, ConfigService config, SetupEnvironment env, CommandHooks hooks) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(hooks, "hooks");
        console.commands().register(command(console, config, env, hooks));
    }

    /** What the caller of {@link #registerCommand(Console, ConfigService, SetupEnvironment, CommandHooks)} learns. */
    public interface CommandHooks {

        /** No hooks. */
        CommandHooks NONE = new CommandHooks() {
            @Override
            public void started() {
            }

            @Override
            public void finished() {
            }
        };

        /** A run of {@code setup} begins; nothing is saved yet. */
        void started();

        /** The run ended: saved, unchanged, cancelled or rolled back after a failure. Always called. */
        void finished();
    }

    /** The {@code setup} command. */
    static Command command(Console console, ConfigService config, SetupEnvironment env) {
        return command(console, config, env, CommandHooks.NONE);
    }

    static Command command(Console console, ConfigService config, SetupEnvironment env, CommandHooks hooks) {
        return Command.builder(COMMAND)
                .description(SetupMessages.COMMAND_DESCRIPTION)
                .executes(context -> {
                    hooks.started();
                    try {
                        run(console, config, env.withLanguage(context.language()));
                    } catch (IOException e) {
                        throw new CommandException(SetupMessages.WRITE_FAILED, String.valueOf(e.getMessage()));
                    } finally {
                        hooks.finished();
                    }
                })
                .build();
    }

    private static Supplier<Optional<String>> groupList(SetupEnvironment env) {
        return env.windows() ? SecretAccess::whoamiGroups : Optional::empty;
    }

    private static SetupException withHeading(Path file, SetupException e) {
        java.util.List<Text> lines = new java.util.ArrayList<>();
        lines.add(SetupMessages.fill(SetupMessages.FILE_PROBLEMS, file.getFileName()));
        lines.addAll(e.problems());
        return new SetupException(lines);
    }
}
