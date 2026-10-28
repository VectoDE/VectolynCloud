package net.vectora.cloud.console.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.io.RegularPaths;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** Creates the {@link ConsoleTerminal} for a {@link TerminalSelector.Selection}. */
public final class TerminalFactory {

    /** History file, relative to the cloud home. */
    public static final String HISTORY_FILE = "state/console.history";

    private static final System.Logger LOG = System.getLogger(TerminalFactory.class.getName());

    private TerminalFactory() {
    }

    /**
     * Opens the console terminal. If JLine cannot open the system terminal, it falls back to
     * {@link PlainTerminal} on {@code in} and {@code out}, with a warning in the log.
     *
     * @param completer      candidates for the line up to the cursor
     * @param historyFile    history file (see {@link #prepareHistory}), or null for none
     * @param historyAllowed which command lines may be stored
     */
    public static ConsoleTerminal open(TerminalSelector.Selection selection, Function<String, List<String>> completer,
                                       Path historyFile, Predicate<String> historyAllowed, InputStream in, PrintStream out) {
        if (selection.mode() == TerminalSelector.Mode.PLAIN) {
            return new PlainTerminal(in, out);
        }
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .name("VectoraCloud")
                    .system(true)
                    .provider(selection.provider())
                    .dumb(true)
                    .build();
            String description = "jline (provider " + selection.provider() + ", type " + terminal.getType() + ")";
            return new JLineTerminal(terminal, completer, historyFile, historyAllowed, description);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, "Could not open the terminal with JLine provider "
                    + selection.provider() + "; reading stdin line by line instead", e);
            return new PlainTerminal(in, out);
        }
    }

    /**
     * Prepares {@code <home>/state/console.history}: creates the folder and the file, restricts the
     * file to the owner. Returns null (history in memory only) if the file is a symbolic link or
     * cannot be prepared; the history holds no secrets, but it should not be readable by others or
     * written through a link either.
     */
    public static Path prepareHistory(CloudHome home) {
        Path file = home.root().resolve(HISTORY_FILE);
        try {
            RegularPaths.requireDirectoryOrMissing(file.getParent());
            Files.createDirectories(file.getParent());
            RegularPaths.requireFileOrMissing(file);
            if (RegularPaths.kind(file) == RegularPaths.Kind.MISSING) {
                Files.createFile(file);
            }
            OwnerOnlyPermissions.restrictFile(file);
            return file;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Console history is kept in memory only: " + e.getMessage());
            return null;
        }
    }
}
