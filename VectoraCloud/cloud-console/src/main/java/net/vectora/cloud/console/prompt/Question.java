package net.vectora.cloud.console.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.console.Messages;
import net.vectora.cloud.console.Text;

/**
 * One question of an assistant, immutable. Create it with a factory, refine it with
 * {@link #defaultValue}, {@link #validate} and {@link #help}, and ask it with {@link Prompter#ask}.
 *
 * <pre>{@code
 * int port = prompter.ask(Question.number(Text.of("Proxy port", "Proxy-Port"), 1, 65535).defaultValue(25565));
 * boolean eula = prompter.ask(Question.yesNo(Text.of("Accept the Minecraft EULA?", "Minecraft-EULA akzeptieren?")));
 * DatabaseMode mode = prompter.ask(Question.choice(Text.of("Database", "Datenbank"), List.of(
 *         Question.option("managed", Text.of("Managed MariaDB", "Verwaltete MariaDB"), DatabaseMode.MANAGED),
 *         Question.option("external", Text.of("External server", "Externer Server"), DatabaseMode.EXTERNAL))));
 * Secret password = prompter.ask(Question.secret(Text.of("Database password", "Datenbank-Passwort"))
 *         .validate(s -> s.length() >= 12 ? null : Text.of("At least 12 characters.", "Mindestens 12 Zeichen.")));
 * }</pre>
 *
 * @param <T> type of the answer
 */
public final class Question<T> {

    /** Kind of question; decides how the answer is read and shown. */
    public enum Kind {
        /** Free text. */
        TEXT,
        /** A whole number in a range. */
        NUMBER,
        /** Yes or no. */
        YES_NO,
        /** One of several options. */
        CHOICE,
        /** A secret: typed masked, never stored in the history, never shown. */
        SECRET
    }

    /** Turns the typed text into an answer. */
    @FunctionalInterface
    public interface AnswerParser<T> {
        /**
         * Parses {@code text} (stripped of surrounding whitespace, except for secrets).
         *
         * @throws InvalidAnswerException if it does not fit; the question is asked again
         */
        T parse(String text) throws InvalidAnswerException;
    }

    /** Checks a parsed answer. */
    @FunctionalInterface
    public interface Validator<T> {
        /** A problem with {@code value}, or null if it is fine. The text must not repeat the value. */
        Text check(T value);
    }

    /**
     * An option of a choice question.
     *
     * @param key   what can be typed instead of the number (case does not matter)
     * @param label shown in the list
     * @param value the answer
     */
    public record Option<T>(String key, Text label, T value) {
        /** All parts are required; the key has no spaces. */
        public Option {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
            if (key.isBlank() || key.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("an option key has no spaces");
            }
        }
    }

    private final Kind kind;
    private final Text prompt;
    private final Text help;
    private final T defaultValue;
    private final AnswerParser<T> parser;
    private final Function<T, String> display;
    private final List<Validator<T>> validators;
    private final List<Option<T>> options;
    private final long min;
    private final long max;

    private Question(Kind kind, Text prompt, Text help, T defaultValue, AnswerParser<T> parser,
                     Function<T, String> display, List<Validator<T>> validators, List<Option<T>> options,
                     long min, long max) {
        this.kind = kind;
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.help = help;
        this.defaultValue = defaultValue;
        this.parser = parser;
        this.display = display;
        this.validators = List.copyOf(validators);
        this.options = List.copyOf(options);
        this.min = min;
        this.max = max;
    }

    /** Free text. Leading and trailing spaces are removed. */
    public static Question<String> text(Text prompt) {
        return new Question<>(Kind.TEXT, prompt, null, null, text -> text, value -> value, List.of(), List.of(), 0, 0);
    }

    /** A whole number from {@code min} to {@code max} (both included). */
    public static Question<Integer> number(Text prompt, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max");
        }
        AnswerParser<Integer> parser = text -> {
            if (!text.matches("[+-]?[0-9]{1,10}")) {
                throw new InvalidAnswerException(Messages.ANSWER_NUMBER, min, max);
            }
            long value = Long.parseLong(text);
            if (value < min || value > max) {
                throw new InvalidAnswerException(Messages.ANSWER_NUMBER, min, max);
            }
            return (int) value;
        };
        return new Question<>(Kind.NUMBER, prompt, null, null, parser, String::valueOf, List.of(), List.of(), min, max);
    }

    /** Yes or no: {@code y}, {@code yes}, {@code j}, {@code ja}, {@code n}, {@code no}, {@code nein}, any case. */
    public static Question<Boolean> yesNo(Text prompt) {
        AnswerParser<Boolean> parser = text -> switch (text.toLowerCase(Locale.ROOT)) {
            case "y", "yes", "j", "ja" -> true;
            case "n", "no", "nein" -> false;
            default -> throw new InvalidAnswerException(Messages.ANSWER_YES_NO);
        };
        return new Question<>(Kind.YES_NO, prompt, null, null, parser, String::valueOf, List.of(), List.of(), 0, 0);
    }

    /** An option of a {@link #choice} question. */
    public static <T> Option<T> option(String key, Text label, T value) {
        return new Option<>(key, label, value);
    }

    /** One of {@code options}: by its number in the list or by its key. */
    public static <T> Question<T> choice(Text prompt, List<Option<T>> options) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("a choice needs options");
        }
        List<Option<T>> copy = List.copyOf(options);
        List<String> keys = new ArrayList<>();
        for (Option<T> option : copy) {
            String key = option.key().toLowerCase(Locale.ROOT);
            if (keys.contains(key)) {
                throw new IllegalArgumentException("option key used twice: " + option.key());
            }
            keys.add(key);
        }
        AnswerParser<T> parser = text -> {
            if (text.matches("[0-9]{1,4}")) {
                int number = Integer.parseInt(text);
                if (number >= 1 && number <= copy.size()) {
                    return copy.get(number - 1).value();
                }
            }
            int index = keys.indexOf(text.toLowerCase(Locale.ROOT));
            if (index < 0) {
                throw new InvalidAnswerException(Messages.ANSWER_CHOICE, copy.size());
            }
            return copy.get(index).value();
        };
        Function<T, String> display = value -> {
            for (Option<T> option : copy) {
                if (option.value().equals(value)) {
                    return option.key();
                }
            }
            return "?";
        };
        return new Question<>(Kind.CHOICE, prompt, null, null, parser, display, List.of(), copy, 0, 0);
    }

    /**
     * A secret, typed masked. Never stored in the history, never shown, not even as default.
     * The answer is taken as typed (spaces included) and wrapped in {@link Secret}.
     */
    public static Question<Secret> secret(Text prompt) {
        return new Question<>(Kind.SECRET, prompt, null, null, Secret::of, value -> Secret.MASK, List.of(), List.of(), 0, 0);
    }

    /** Free text turned into any type by {@code parser}; {@code display} shows the default. */
    public static <T> Question<T> custom(Text prompt, AnswerParser<T> parser, Function<T, String> display) {
        return new Question<>(Kind.TEXT, prompt, null, null, Objects.requireNonNull(parser, "parser"),
                Objects.requireNonNull(display, "display"), List.of(), List.of(), 0, 0);
    }

    /** Same question; pressing Enter without an answer gives {@code value}. */
    public Question<T> defaultValue(T value) {
        return new Question<>(kind, prompt, help, Objects.requireNonNull(value, "value"), parser, display,
                validators, options, min, max);
    }

    /** Same question with one more check. Checks run in the order they were added. */
    public Question<T> validate(Validator<T> validator) {
        List<Validator<T>> more = new ArrayList<>(validators);
        more.add(Objects.requireNonNull(validator, "validator"));
        return new Question<>(kind, prompt, help, defaultValue, parser, display, more, options, min, max);
    }

    /** Same question with a help line shown before it. */
    public Question<T> help(Text text) {
        return new Question<>(kind, prompt, Objects.requireNonNull(text, "text"), defaultValue, parser, display,
                validators, options, min, max);
    }

    /** The kind. */
    public Kind kind() {
        return kind;
    }

    /** The question text. */
    public Text prompt() {
        return prompt;
    }

    /** The help line, or null. */
    public Text helpText() {
        return help;
    }

    /** The default, or null. */
    public T defaultAnswer() {
        return defaultValue;
    }

    /** The options of a choice; empty otherwise. */
    public List<Option<T>> options() {
        return options;
    }

    /** Lower bound of a number question. */
    public long min() {
        return min;
    }

    /** Upper bound of a number question. */
    public long max() {
        return max;
    }

    /** How the default is shown. */
    String display(T value) {
        return display.apply(value);
    }

    /** Parses and validates one answer. */
    T accept(String text) throws InvalidAnswerException {
        T value = parser.parse(text);
        for (Validator<T> validator : validators) {
            Text problem = validator.check(value);
            if (problem != null) {
                throw new InvalidAnswerException(problem);
            }
        }
        return value;
    }
}
