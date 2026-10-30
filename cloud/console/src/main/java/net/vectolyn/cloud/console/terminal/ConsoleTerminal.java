package net.vectolyn.cloud.console.terminal;

/**
 * Input and output of the console. Two implementations: {@link JLineTerminal} on a real terminal
 * (line editing, history, tab completion) and {@link PlainTerminal} without one (Docker without
 * {@code -t}, Pterodactyl, systemd), which reads stdin line by line. Tests use either on streams.
 *
 * <p>Thread-safe for {@link #print}: log lines from other threads appear above the prompt while a
 * line is being typed. Reading happens on one thread at a time.
 */
public interface ConsoleTerminal extends AutoCloseable {

    /** What a line is read for. Only {@link #COMMAND} lines may reach the history. */
    enum LineKind {
        /** A command at the main prompt. */
        COMMAND,
        /** The answer to a question of an assistant. Never stored in the history. */
        ANSWER,
        /** A secret: shown masked, never stored in the history. */
        SECRET
    }

    /** Reads one line. {@code prompt} may contain ANSI codes only if {@link #ansi()} is true. */
    ReadResult readLine(String prompt, LineKind kind);

    /** Prints a line (or several) above the current prompt. */
    void print(String text);

    /** Whether the terminal understands ANSI colors and the screen can be cleared. */
    boolean ansi();

    /** Whether there is a real terminal (line editing, Ctrl+C reaches the console). */
    boolean interactive();

    /**
     * Whether a {@link LineKind#SECRET} line is read without showing what is typed. Only a terminal
     * that can switch off the echo can promise that; JLine's dumb terminal and the plain console
     * cannot, so the question API warns before a secret there.
     */
    boolean hidesSecrets();

    /** Clears the screen if {@link #ansi()}; does nothing otherwise. */
    void clearScreen();

    /** Short description for the log, for example {@code jline (provider jni)} or {@code plain}. */
    String description();

    /** Restores the terminal and saves the history. */
    @Override
    void close();
}
