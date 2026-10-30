package net.vectora.cloud.template;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Turns command blocks off in the {@code server.properties} of a runtime folder: after rendering,
 * the node always sets {@value #KEY}{@code =false} for Paper, PandaSpigot and Fabric, whatever the
 * template or an operator wrote there. There is no setting to turn this off: a command block gives
 * whoever reaches one the power of the console. The bridges also remove the blocks themselves.
 *
 * <p>The file is read and written as ISO-8859-1, which maps every byte to one character, so every
 * other line keeps its bytes. Keys are found the way the server reads the file
 * ({@link Properties}): any spelling of the key (escapes, {@code :} or a blank as separator,
 * continued lines) counts, every occurrence is replaced by one line {@value #LINE}, and a file
 * without the key gets that line at the end. The result is checked with {@link Properties} again.
 */
final class CommandBlockSetting {

    /** The file, relative to the runtime folder. */
    static final String FILE = "server.properties";
    /** The key. */
    static final String KEY = "enable-command-block";
    /** The line the node writes. */
    static final String LINE = KEY + "=false";

    /** What {@link #enforce} did. */
    record Outcome(boolean changed, boolean overwritten) {
    }

    private CommandBlockSetting() {
    }

    /**
     * Makes {@value #FILE} in {@code runtime} say {@value #LINE}, writing it only if something
     * changes. A missing file gets just that line (the server adds the rest on its first start).
     *
     * @param runtime runtime folder
     * @return whether the file changed, and whether it had another value than {@code false}
     * @throws TemplateException if the path is unsafe (a link, a folder) or the result does not read
     *     as {@code false}
     * @throws IOException if reading or writing fails
     */
    static Outcome enforce(Path runtime) throws TemplateException, IOException {
        Path target = RuntimePaths.check(runtime, FILE);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        String text = "";
        if (exists) {
            try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                text = new String(in.readAllBytes(), ISO_8859_1);
            }
        }
        Fixed fixed = fix(text);
        if (!value(fixed.text()).equals(Optional.of("false"))) {
            throw new TemplateException(FILE, "does not read as properties (a malformed \\u escape?), so the cloud"
                    + " cannot make sure of " + LINE);
        }
        if (exists && fixed.text().equals(text)) {
            return new Outcome(false, false);
        }
        RuntimePaths.write(runtime, FILE, fixed.text().getBytes(ISO_8859_1), false);
        return new Outcome(true, fixed.overwritten());
    }

    /** The fixed text and whether a value other than {@code false} was replaced. */
    record Fixed(String text, boolean overwritten) {
    }

    /**
     * Returns {@code text} with every {@value #KEY} replaced by {@value #LINE}.
     *
     * @param text content of {@value #FILE}, maybe empty
     * @return the new content (equal to {@code text} if it already had exactly one {@value #LINE}
     *     and no other occurrence) and whether another value was replaced
     */
    static Fixed fix(String text) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        // Physical lines and the line break after each (empty after the last one), kept as they are.
        List<String> lines = new ArrayList<>();
        List<String> breaks = new ArrayList<>();
        int start = 0;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, at));
                boolean crlf = c == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n';
                breaks.add(crlf ? "\r\n" : String.valueOf(c));
                at += crlf ? 1 : 0;
                start = at + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
            breaks.add("");
        }
        StringBuilder out = new StringBuilder(text.length() + LINE.length() + 2);
        boolean found = false;
        boolean overwritten = false;
        boolean open = false;
        int i = 0;
        while (i < lines.size()) {
            // A logical line: a line ending in an odd number of backslashes goes on in the next one;
            // a comment line never does.
            boolean comment = isComment(lines.get(i));
            int end = i;
            while (!comment && end < lines.size() - 1 && continues(lines.get(end))) {
                end++;
            }
            // Whether the file ends inside this logical line (its last line asks for one more).
            open = !comment && end == lines.size() - 1 && continues(lines.get(end));
            StringBuilder logical = new StringBuilder();
            for (int j = i; j <= end; j++) {
                logical.append(lines.get(j)).append('\n');
            }
            Optional<String> value = comment ? Optional.empty() : value(logical.toString());
            if (value.isEmpty()) {
                for (int j = i; j <= end; j++) {
                    out.append(lines.get(j)).append(breaks.get(j));
                }
            } else {
                if (!value.get().equals("false")) {
                    overwritten = true;
                }
                if (!found) {
                    out.append(LINE).append(breaks.get(end));
                    found = true;
                }
            }
            i = end + 1;
        }
        if (!found) {
            // The file's own last line break, so that a lone CR and an added LF never merge into one.
            String lastBreak = breaks.isEmpty() ? "" : breaks.get(breaks.size() - 1);
            String added = lastBreak.isEmpty() ? eol : lastBreak;
            if (!lines.isEmpty() && lastBreak.isEmpty()) {
                out.append(added);
            }
            if (open) {
                // The file ends in a continued line: an empty line ends it, or the key would join it.
                out.append(added);
            }
            out.append(LINE).append(added);
        }
        return new Fixed(out.toString(), overwritten);
    }

    /** The value of {@value #KEY} in a properties text, as the server reads it. */
    private static Optional<String> value(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            // A malformed escape: the server could not read it either; treat as no value here.
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getProperty(KEY));
    }

    /** A comment line as {@link Properties} sees it: only blank, tab and form feed count as white space. */
    private static boolean isComment(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\f')) {
            i++;
        }
        return i < line.length() && (line.charAt(i) == '#' || line.charAt(i) == '!');
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
