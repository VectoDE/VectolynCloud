package net.vectolyn.cloud.console.command;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a command line into words.
 *
 * <ul>
 *   <li>Spaces and tabs separate words.</li>
 *   <li>{@code 'single quotes'} keep everything inside literally.</li>
 *   <li>{@code "double quotes"} keep everything inside literally, except {@code \"} and {@code \\}.
 *       Other backslashes stay, so {@code "C:\Vectolyn"} works as typed.</li>
 *   <li>Outside quotes a backslash takes the next character literally ({@code a\ b} is one word).</li>
 *   <li>Quoted and unquoted parts next to each other form one word ({@code ab"c d"} is {@code abc d});
 *       {@code ""} is an empty word.</li>
 * </ul>
 *
 * <p>Refused: other control characters (including line breaks and the Unicode line and paragraph
 * separators), an unclosed quote, a backslash at the end, more than {@value #MAX_LENGTH} characters
 * or more than {@value #MAX_WORDS} words. A word never contains a control character.
 */
public final class CommandLineParser {

    /** Longest accepted line, in UTF-16 characters. */
    public static final int MAX_LENGTH = 4096;
    /** Most words in one line. */
    public static final int MAX_WORDS = 64;

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._:/@+=,%-]+");

    private CommandLineParser() {
    }

    /**
     * The words of {@code line}.
     *
     * @throws CommandSyntaxException if the line breaks one of the rules above
     */
    public static List<String> tokenize(String line) throws CommandSyntaxException {
        return scan(line, false).words();
    }

    /**
     * Result of a lenient scan, for tab completion.
     *
     * @param words      the words, the last one possibly unfinished
     * @param openWord   whether the line ends inside a word (the last word is being typed); false
     *                   if it ends with a separator, so the next word starts empty
     */
    public record Partial(List<String> words, boolean openWord) {
    }

    /**
     * Splits a line that is still being typed. Never fails: an unclosed quote counts as closed at the
     * end, a trailing backslash and control characters are ignored, limits are not checked.
     */
    public static Partial partial(String line) {
        try {
            return scan(line, true);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("lenient scan cannot fail", e);
        }
    }

    /**
     * Writes {@code word} so that {@link #tokenize} reads it back unchanged: as is when it only has
     * safe characters, otherwise in single quotes, or in double quotes if it contains a single quote.
     *
     * @throws IllegalArgumentException if the word contains a control character
     */
    public static String quote(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (isForbidden(word.charAt(i))) {
                throw new IllegalArgumentException("a word cannot contain control characters");
            }
        }
        if (PLAIN.matcher(word).matches()) {
            return word;
        }
        if (word.indexOf('\'') < 0) {
            return "'" + word + "'";
        }
        return "\"" + word.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Joins words with spaces, each {@link #quote quoted} as needed. */
    public static String join(List<String> words) {
        List<String> quoted = new ArrayList<>(words.size());
        for (String word : words) {
            quoted.add(quote(word));
        }
        return String.join(" ", quoted);
    }

    /** Characters that may not appear in a line (tab is a separator, not forbidden). */
    static boolean isForbidden(char c) {
        return (c < 0x20 && c != '\t') || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == '\u2028' || c == '\u2029';
    }

    private static Partial scan(String line, boolean lenient) throws CommandSyntaxException {
        if (line == null) {
            line = "";
        }
        if (!lenient && line.length() > MAX_LENGTH) {
            throw new CommandSyntaxException(CommandSyntaxException.Reason.TOO_LONG, MAX_LENGTH);
        }
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (isForbidden(c)) {
                if (!lenient) {
                    throw new CommandSyntaxException(CommandSyntaxException.Reason.CONTROL_CHARACTER, i);
                }
                i++;
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (inWord) {
                    addWord(words, current, lenient);
                    inWord = false;
                }
                i++;
            } else if (c == '\'') {
                inWord = true;
                int end = line.indexOf('\'', i + 1);
                if (end < 0) {
                    if (!lenient) {
                        throw new CommandSyntaxException(CommandSyntaxException.Reason.UNCLOSED_QUOTE, i);
                    }
                    appendAllowed(current, line, i + 1, n);
                    i = n;
                } else {
                    appendChecked(current, line, i + 1, end, lenient);
                    i = end + 1;
                }
            } else if (c == '"') {
                inWord = true;
                int start = i;
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '"') {
                        closed = true;
                        i++;
                        break;
                    }
                    if (isForbidden(d)) {
                        if (!lenient) {
                            throw new CommandSyntaxException(CommandSyntaxException.Reason.CONTROL_CHARACTER, i);
                        }
                        i++;
                        continue;
                    }
                    if (d == '\\' && i + 1 < n && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                        current.append(line.charAt(i + 1));
                        i += 2;
                    } else {
                        current.append(d);
                        i++;
                    }
                }
                if (!closed && !lenient) {
                    throw new CommandSyntaxException(CommandSyntaxException.Reason.UNCLOSED_QUOTE, start);
                }
            } else if (c == '\\') {
                if (i + 1 >= n) {
                    if (!lenient) {
                        throw new CommandSyntaxException(CommandSyntaxException.Reason.DANGLING_ESCAPE, i);
                    }
                    i++;
                    continue;
                }
                char next = line.charAt(i + 1);
                if (isForbidden(next)) {
                    if (!lenient) {
                        throw new CommandSyntaxException(CommandSyntaxException.Reason.CONTROL_CHARACTER, i + 1);
                    }
                    i += 2;
                    continue;
                }
                inWord = true;
                current.append(next);
                i += 2;
            } else {
                inWord = true;
                current.append(c);
                i++;
            }
        }
        boolean open = inWord;
        if (inWord) {
            addWord(words, current, lenient);
        }
        return new Partial(List.copyOf(words), open);
    }

    private static void addWord(List<String> words, StringBuilder current, boolean lenient) throws CommandSyntaxException {
        if (!lenient && words.size() >= MAX_WORDS) {
            throw new CommandSyntaxException(CommandSyntaxException.Reason.TOO_MANY_WORDS, MAX_WORDS);
        }
        words.add(current.toString());
        current.setLength(0);
    }

    private static void appendChecked(StringBuilder out, String line, int from, int to, boolean lenient)
            throws CommandSyntaxException {
        for (int k = from; k < to; k++) {
            char c = line.charAt(k);
            if (isForbidden(c)) {
                if (!lenient) {
                    throw new CommandSyntaxException(CommandSyntaxException.Reason.CONTROL_CHARACTER, k);
                }
                continue;
            }
            out.append(c);
        }
    }

    private static void appendAllowed(StringBuilder out, String line, int from, int to) {
        for (int k = from; k < to; k++) {
            char c = line.charAt(k);
            if (!isForbidden(c)) {
                out.append(c);
            }
        }
    }
}
