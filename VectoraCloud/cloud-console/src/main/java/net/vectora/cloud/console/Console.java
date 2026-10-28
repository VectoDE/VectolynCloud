package net.vectora.cloud.console;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.command.CommandDispatcher;
import net.vectora.cloud.console.command.CommandRegistry;
import net.vectora.cloud.console.prompt.Prompter;
import net.vectora.cloud.console.terminal.ConsoleTerminal;
import net.vectora.cloud.console.terminal.ReadResult;
import net.vectora.cloud.console.terminal.TerminalFactory;
import net.vectora.cloud.console.terminal.TerminalSelector;

/**
 * The console of the cloud: reads commands, runs them through the {@link CommandRegistry}, and
 * offers {@link #prompter()} to assistants and {@link #output()} to everyone who writes to it.
 *
 * <pre>{@code
 * Console console = Console.builder()
 *         .home(config.home())
 *         .language(() -> config.snapshot().cloud().language())
 *         .build();
 * BuiltinCommands.register(console, config, node::shutdown);
 * console.start();
 * }</pre>
 *
 * <p>Ending: Ctrl+C and Ctrl+D on a terminal run the {@code shutdown} command, exactly as if it was
 * typed. Without a terminal the end of stdin only stops reading; the cloud keeps running and is
 * stopped by a signal.
 */
public final class Console implements AutoCloseable {

    /** Name of the command that Ctrl+C and Ctrl+D run. */
    public static final String SHUTDOWN_COMMAND = "shutdown";

    private static final System.Logger LOG = System.getLogger(Console.class.getName());

    private final ConsoleTerminal terminal;
    private final CommandRegistry registry;
    private final TerminalOutput output;
    private final Prompter prompter;
    private final CommandDispatcher dispatcher;
    private final Supplier<String> prompt;
    private volatile boolean running = true;
    private Thread thread;

    private Console(Builder builder, CommandRegistry registry, ConsoleTerminal terminal) {
        this.registry = registry;
        this.terminal = terminal;
        this.output = new TerminalOutput(terminal, builder.language);
        this.prompter = new Prompter(terminal, output);
        this.dispatcher = new CommandDispatcher(registry, output, prompter);
        this.prompt = builder.prompt;
    }

    /** Starts a console. */
    public static Builder builder() {
        return new Builder();
    }

    /** Commands; other modules register theirs here. */
    public CommandRegistry commands() {
        return registry;
    }

    /** Where to write; lines appear above the prompt. */
    public ConsoleOutput output() {
        return output;
    }

    /** Asks questions; the base of the setup assistant. */
    public Prompter prompter() {
        return prompter;
    }

    /** Runs typed lines; also usable for lines from elsewhere (tests, a remote console later). */
    public CommandDispatcher dispatcher() {
        return dispatcher;
    }

    /** The terminal in use. */
    public ConsoleTerminal terminal() {
        return terminal;
    }

    /** Whether the loop is still reading. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Reads and runs commands on the calling thread until {@link #stop()}, a shutdown, or the end of
     * stdin without a terminal.
     */
    public void run() {
        LOG.log(System.Logger.Level.INFO, "Console: {0}", terminal.description());
        while (running) {
            String text = terminal.interactive() ? Style.PROMPT.apply(prompt.get(), terminal.ansi()) : "";
            ReadResult result = terminal.readLine(text, ConsoleTerminal.LineKind.COMMAND);
            if (!running) {
                return;
            }
            switch (result) {
                case ReadResult.Line line -> dispatcher.execute(line.text());
                case ReadResult.Interrupted interrupted -> shutdownFromKey();
                case ReadResult.EndOfInput end -> {
                    if (terminal.interactive()) {
                        shutdownFromKey();
                    } else {
                        LOG.log(System.Logger.Level.INFO, "Console input ended; the cloud keeps running. "
                                + "Stop it with a signal (SIGTERM, docker stop).");
                        running = false;
                    }
                }
            }
        }
    }

    /** Runs {@link #run()} on a daemon thread named {@code vectora-console}. Idempotent. */
    public synchronized Thread start() {
        if (thread == null) {
            thread = Thread.ofPlatform().daemon().name("vectora-console").start(this::run);
        }
        return thread;
    }

    /** Stops reading after the current line. */
    public void stop() {
        running = false;
    }

    /** Stops reading, restores the terminal and saves the history. */
    @Override
    public void close() {
        stop();
        terminal.close();
    }

    /**
     * A {@link java.util.logging.Handler} that prints log records above the prompt.
     *
     * @param redactor applied to every line before it is printed, for example
     *                 {@code RedactingFilter::redact} from cloud-config; {@link UnaryOperator#identity()} for none
     */
    public ConsoleLogHandler logHandler(UnaryOperator<String> redactor) {
        return new ConsoleLogHandler(terminal, redactor);
    }

    private void shutdownFromKey() {
        if (registry.find(SHUTDOWN_COMMAND).isPresent()) {
            dispatcher.execute(SHUTDOWN_COMMAND);
        } else {
            running = false;
        }
    }

    /** Builds a {@link Console}. */
    public static final class Builder {

        private Supplier<Language> language = () -> Language.EN;
        private Supplier<String> prompt = () -> "vectora> ";
        private ConsoleTerminal terminal;
        private CloudHome home;
        private Predicate<String> historyFilter = line -> true;
        private InputStream in = System.in;
        private PrintStream out = System.out;

        private Builder() {
        }

        /** Language of texts, read again for every line (default English). */
        public Builder language(Supplier<Language> value) {
            this.language = Objects.requireNonNull(value, "language");
            return this;
        }

        /** Prompt text (default {@code vectora> }). */
        public Builder prompt(Supplier<String> value) {
            this.prompt = Objects.requireNonNull(value, "prompt");
            return this;
        }

        /** Cloud home; the history goes to {@code state/console.history} there. Without it, no history file. */
        public Builder home(CloudHome value) {
            this.home = value;
            return this;
        }

        /**
         * An extra rule for the history: lines for which it returns false are not stored. Lines of
         * sensitive commands, answers and secrets are never stored anyway. A good extra rule is
         * "does not contain a known secret value".
         */
        public Builder historyFilter(Predicate<String> value) {
            this.historyFilter = Objects.requireNonNull(value, "historyFilter");
            return this;
        }

        /** Streams for the plain console without a terminal (default {@code System.in}/{@code System.out}). */
        public Builder streams(InputStream input, PrintStream output) {
            this.in = Objects.requireNonNull(input, "input");
            this.out = Objects.requireNonNull(output, "output");
            return this;
        }

        /** Uses this terminal instead of choosing one (tests, embedding). */
        public Builder terminal(ConsoleTerminal value) {
            this.terminal = value;
            return this;
        }

        /** Chooses the terminal ({@link TerminalSelector#detect()}) unless one was given, and builds the console. */
        public Console build() {
            CommandRegistry registry = new CommandRegistry();
            ConsoleTerminal chosen = terminal;
            if (chosen == null) {
                TerminalSelector.Selection selection = TerminalSelector.detect();
                LOG.log(System.Logger.Level.DEBUG, "Console selection: {0} ({1})", selection.mode(), selection.reason());
                Path history = home == null || selection.mode() == TerminalSelector.Mode.PLAIN
                        ? null : TerminalFactory.prepareHistory(home);
                Predicate<String> filter = historyFilter;
                chosen = TerminalFactory.open(selection, line -> CommandDispatcher.complete(registry, line), history,
                        line -> !registry.isSensitive(line) && filter.test(line), in, out);
            }
            return new Console(this, registry, chosen);
        }
    }
}
