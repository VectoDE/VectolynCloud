package net.vectora.cloud.console;

import java.util.Objects;
import java.util.function.Supplier;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.terminal.ConsoleTerminal;

/** {@link ConsoleOutput} on a {@link ConsoleTerminal}, colored only where the terminal supports it. */
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
        terminal.print(Style.HEADING.apply(text, terminal.ansi()));
    }

    @Override
    public void success(String text) {
        terminal.print(Style.SUCCESS.apply(text, terminal.ansi()));
    }

    @Override
    public void warning(String text) {
        terminal.print(Style.WARNING.apply(text, terminal.ansi()));
    }

    @Override
    public void error(String text) {
        terminal.print(Style.ERROR.apply(text, terminal.ansi()));
    }
}
