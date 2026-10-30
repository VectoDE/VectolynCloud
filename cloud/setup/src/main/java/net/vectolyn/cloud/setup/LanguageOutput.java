package net.vectolyn.cloud.setup;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.ConsoleOutput;

/**
 * The console output in the language the operator picked in the first question. {@code cloud.yml}
 * holds the language only after saving, so the console's own setting would still be the old one.
 */
final class LanguageOutput implements ConsoleOutput {

    private final ConsoleOutput delegate;
    private final AtomicReference<Language> language;

    LanguageOutput(ConsoleOutput delegate, Language initial) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.language = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    void set(Language value) {
        language.set(Objects.requireNonNull(value, "value"));
    }

    @Override
    public Language language() {
        return language.get();
    }

    @Override
    public void println(String text) {
        delegate.println(text);
    }

    @Override
    public void heading(String text) {
        delegate.heading(text);
    }

    @Override
    public void success(String text) {
        delegate.success(text);
    }

    @Override
    public void warning(String text) {
        delegate.warning(text);
    }

    @Override
    public void error(String text) {
        delegate.error(text);
    }
}
