package net.vectolyn.cloud.console;

import java.util.Objects;
import java.util.function.Supplier;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.terminal.ConsoleTerminal;

/**
 * {@link ConsoleOutput} on a {@link ConsoleTerminal}, colored only where the terminal supports it.
 *
 * <p>Headings, success lines, warnings and errors often carry text from outside the console, for
 * example a file name from a directory listing inside a configuration error. They go through
 * {@link #printable} before the style is added, so such text cannot send escape sequences to the
 * terminal. {@link #println} stays raw, because the question API passes styled text through it.
 */
final class TerminalOutput implements ConsoleOutput {

    private final ConsoleTerminal terminal;
    private final Supplier<Language> language;

    TerminalOutput(ConsoleTerminal terminal, Supplier<Language> language) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.language = Objects.requireNonNull(language, "language");
    }

    @Override
    public Language language() {
        Language value = language.get();
        return value == null ? Language.EN : value;
    }

    @Override
    public void println(String text) {
        terminal.print(text);
    }

    @Override
    public void heading(String text) {
        terminal.print(Style.HEADING.apply(printable(text), terminal.ansi()));
    }

    @Override
    public void success(String text) {
        terminal.print(Style.SUCCESS.apply(printable(text), terminal.ansi()));
    }

    @Override
    public void warning(String text) {
        terminal.print(Style.WARNING.apply(printable(text), terminal.ansi()));
    }

    @Override
    public void error(String text) {
        terminal.print(Style.ERROR.apply(printable(text), terminal.ansi()));
    }

    /**
     * {@code text} without control characters: line breaks ({@code \n}, {@code \r\n}) and tabs
     * stay, every other C0 or C1 control character (escape, a lone carriage return, ...) becomes
     * {@code ?}.
     */
    static String printable(String text) {
        String value = text.replace("\r\n", "\n");
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean control = (c < 0x20 && c != '\n' && c != '\t') || c == 0x7f || (c >= 0x80 && c < 0xa0);
            if (control && out == null) {
                out = new StringBuilder(value.length()).append(value, 0, i);
            }
            if (out != null) {
                out.append(control ? '?' : c);
            }
        }
        return out == null ? value : out.toString();
    }
}
