package net.vectora.cloud.setup;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.Text;

/**
 * The setup cannot be done as asked: a setup file with problems, or a start that may not ask and
 * has no file. Nothing was written. The texts never contain a value from the file.
 */
public final class SetupException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<Text> problems;

    /** The setup failed for {@code problems}. */
    public SetupException(List<Text> problems) {
        super(Objects.requireNonNull(problems, "problems").isEmpty() ? "setup failed" : describe(problems, Language.EN));
        this.problems = List.copyOf(problems);
    }

    /** From the errors of the strict reader or the validator. */
    public static SetupException of(ConfigException e) {
        List<Text> texts = new ArrayList<>();
        for (ConfigError error : e.errors()) {
            texts.add(Text.of(error.describe(Language.EN), error.describe(Language.DE)));
        }
        return new SetupException(texts);
    }

    /** The problems, in order. */
    public List<Text> problems() {
        return problems;
    }

    /** All problems, one per line, in {@code language}. */
    public String describe(Language language) {
        return describe(problems, language);
    }

    private static String describe(List<Text> problems, Language language) {
        StringBuilder out = new StringBuilder();
        for (Text problem : problems) {
            if (!out.isEmpty()) {
                out.append(System.lineSeparator());
            }
            out.append(problem.get(language));
        }
        return out.toString();
    }
}
