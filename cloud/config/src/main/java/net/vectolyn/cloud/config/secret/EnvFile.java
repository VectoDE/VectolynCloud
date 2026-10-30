package net.vectolyn.cloud.config.secret;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Content of a {@code KEY=VALUE} file such as {@code secrets/vectolyn.env}, line by line, so that
 * comments and blank lines survive a rewrite. Immutable.
 */
public final class EnvFile {

    /** One line of the file. */
    public sealed interface Line permits Blank, Comment, Entry {
        /** The line as it is written to the file, without line break. */
        String text();
    }

    /**
     * An empty line or one with only spaces and tabs.
     *
     * @param text the line as found
     */
    public record Blank(String text) implements Line {
    }

    /**
     * A comment line ({@code #} after optional spaces or tabs).
     *
     * @param text the whole line, including {@code #}
     */
    public record Comment(String text) implements Line {
    }

    /**
     * A {@code KEY=VALUE} line.
     *
     * @param key   the key
     * @param value the value, possibly empty (unset)
     */
    public record Entry(String key, Secret value) implements Line {

        /** Checks the arguments. */
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String text() {
            return key + "=" + value.reveal();
        }

        @Override
        public String toString() {
            return "Entry[key=" + key + ", value=" + value + "]";
        }
    }

    private static final EnvFile EMPTY = new EnvFile(List.of());

    private final List<Line> lines;

    private EnvFile(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    /** A file without lines. */
    public static EnvFile empty() {
        return EMPTY;
    }

    /** A file from already checked lines (used by the parser). */
    static EnvFile of(List<Line> lines) {
        return new EnvFile(lines);
    }

    /** All lines in order. */
    public List<Line> lines() {
        return lines;
    }

    /** The value of {@code key}, possibly empty, or nothing if the key is absent. */
    public Optional<Secret> get(String key) {
        return entries().stream().filter(e -> e.key().equals(key)).map(Entry::value).findFirst();
    }

    /** Whether {@code key} is present with a non-empty value. */
    public boolean hasValue(String key) {
        return get(key).map(v -> !v.isEmpty()).orElse(false);
    }

    /** All keys, in file order. */
    public Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        entries().forEach(e -> keys.add(e.key()));
        return keys;
    }

    /** All {@code KEY=VALUE} lines. */
    public List<Entry> entries() {
        return lines.stream().filter(Entry.class::isInstance).map(Entry.class::cast).toList();
    }

    /**
     * A copy where {@code key} has {@code value}, but only if the key has no value yet. An existing
     * non-empty value is never replaced. An empty entry ({@code KEY=}, as copied from the example
     * file) is filled in place; a missing key is appended, after {@code comment} if one is given.
     */
    public EnvFile withValueIfAbsent(String key, Secret value, String comment) {
        EnvFileParser.checkKey(key);
        EnvFileParser.checkValue(value.reveal());
        if (hasValue(key)) {
            return this;
        }
        List<Line> copy = new ArrayList<>(lines);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) instanceof Entry entry && entry.key().equals(key)) {
                copy.set(i, new Entry(key, value));
                return new EnvFile(copy);
            }
        }
        if (comment != null) {
            if (!copy.isEmpty() && !(copy.getLast() instanceof Blank)) {
                copy.add(new Blank(""));
            }
            for (String part : comment.split("\n")) {
                copy.add(new Comment("# " + part));
            }
        }
        copy.add(new Entry(key, value));
        return new EnvFile(copy);
    }

    /**
     * A copy where {@code key} has {@code value}, replacing a value it already has. The entry keeps
     * its place in the file; a missing key is appended like in {@link #withValueIfAbsent}. Only for
     * values the operator chose (for example the password of an external database), never for
     * generated secrets, which must stay as they are.
     */
    public EnvFile withValue(String key, Secret value, String comment) {
        EnvFileParser.checkKey(key);
        EnvFileParser.checkValue(value.reveal());
        List<Line> copy = new ArrayList<>(lines);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) instanceof Entry entry && entry.key().equals(key)) {
                copy.set(i, new Entry(key, value));
                return new EnvFile(copy);
            }
        }
        return withValueIfAbsent(key, value, comment);
    }

    /** The file content with {@code \n} line endings. */
    public String render() {
        StringBuilder out = new StringBuilder();
        for (Line line : lines) {
            out.append(line.text()).append('\n');
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EnvFile file && file.lines.equals(lines);
    }

    @Override
    public int hashCode() {
        return lines.hashCode();
    }

    /** Lists the keys only, never the values. */
    @Override
    public String toString() {
        return "EnvFile" + keys();
    }
}
