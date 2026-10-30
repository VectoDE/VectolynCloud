package net.vectolyn.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns off Spigot's own restart in the {@code spigot.yml} of a runtime folder.
 *
 * <p>Spigot, Paper and PandaSpigot run {@code settings.restart-script} by themselves: on
 * {@code /restart}, and when the watchdog ends a hung server with {@code restart-on-crash} on. If
 * the script exists as a file, the server starts it from a shutdown hook ({@code sh script}, on
 * Windows {@code cmd /c start script}); that is a second server outside the node, without a service
 * token and maybe on the same port and world. If it does not exist, the server only stops. Spigot
 * fills in {@code restart-on-crash: true} and {@code restart-script: ./start.sh} when the keys are
 * missing.
 *
 * <p>So after rendering, the node sets {@code restart-script: ''} under {@code settings:}: an empty
 * path is never a file, so {@code /restart} just stops the server and the node decides whether it
 * starts again. {@code restart-on-crash} stays {@code true}, because on PandaSpigot (Spigot 1.8.8)
 * it is the only way the watchdog ends a hung server at all: with {@code false} it logs a thread dump
 * and leaves the hung process running, which the node cannot tell from a healthy one. With the empty
 * script, {@code true} means "stop", nothing more. Paper ends a hung server either way. Every other
 * line stays as it is. A file without a {@code settings:} block gets one; a missing file gets just
 * that block (Spigot adds the rest on its first start).
 */
final class SpigotRestart {

    /** The file, relative to the runtime folder. */
    static final String FILE = "spigot.yml";

    private static final String ON_CRASH = "restart-on-crash";
    private static final String SCRIPT = "restart-script";
    private static final String ON_CRASH_LINE = ON_CRASH + ": true";
    private static final String SCRIPT_LINE = SCRIPT + ": ''";
    private static final String DEFAULT_INDENT = "  ";
    /** Byte order mark, written as a number so that the source holds no invisible character. */
    private static final char BOM = (char) 0xFEFF;

    /** A top-level {@code settings} key, maybe quoted, maybe after a byte order mark. */
    private static final Pattern SETTINGS = Pattern.compile("\\x{FEFF}?(settings|'settings'|\"settings\")[ \t]*:(.*)");
    /** One of the two keys at the start of a line inside the block, maybe quoted. */
    private static final Pattern KEY = Pattern.compile("(['\"]?)(" + ON_CRASH + "|" + SCRIPT + ")\\1[ \t]*:(?:[ \t].*)?");
    private static final Pattern ON_CRASH_SET = Pattern.compile(ON_CRASH + ":[ \t]+true[ \t]*(?:#.*)?");
    private static final Pattern SCRIPT_SET =Pattern.compile(SCRIPT + ":[ \t]*(?:''|\"\")[ \t]*(?:#.*)?");
    /** A bare document marker, maybe with a comment. */
    private static final Pattern MARKER = Pattern.compile("(?:---|\\.\\.\\.)(?:[ \t]+#.*|[ \t]*)");
    /**
     * A key this class can read at the start of a line: plain (starting with a letter, digit or
     * {@code _}), single-quoted, or double-quoted without escapes, then {@code :}. Anything else (an
     * explicit key {@code ?}, an anchor, a tag, an escape) could spell {@code settings} or one of the
     * two keys in a way this class does not see, and a later duplicate would win over the fixed line.
     */
    private static final Pattern SIMPLE_KEY = Pattern.compile(
            "(?:[A-Za-z0-9_][^:#]*?|'(?:[^']|'')*'|\"[^\"\\\\]*\")[ \t]*:(?:[ \t].*)?");

    private SpigotRestart() {
    }

    /**
     * Makes {@value #FILE} in {@code runtime} turn off Spigot's restart ({@code restart-script: ''},
     * {@code restart-on-crash: true}), writing it only if something changes.
     *
     * @param runtime runtime folder
     * @return {@value #FILE} if the file was created or changed, otherwise empty
     * @throws TemplateException if the file is not UTF-8, its {@code settings} are no block mapping,
     *     a key it must read is no plain or simply quoted key, or the path is unsafe (a link, a folder)
     * @throws IOException if reading or writing fails
     */
    static Optional<String> enforce(Path runtime) throws TemplateException, IOException {
        Path target = RuntimePaths.check(runtime, FILE);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        String text = exists ? read(target) : "";
        String fixed = fix(text);
        if (exists && fixed.equals(text)) {
            return Optional.empty();
        }
        RuntimePaths.write(runtime, FILE, fixed.getBytes(UTF_8), false);
        return Optional.of(FILE);
    }

    /**
     * Returns {@code text} with {@code restart-script: ''} and {@code restart-on-crash: true}.
     *
     * @param text content of {@value #FILE}, maybe empty
     * @return the new content; equal to {@code text} if both settings were right already
     * @throws TemplateException if {@code settings} is no block mapping or appears twice, or a
     *     top-level key or a key directly in {@code settings} is no plain or simply quoted key
     */
    static String fix(String text) throws TemplateException {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r\n|\n", -1)));

        int settings = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = SETTINGS.matcher(line);
            if (m.matches()) {
                String rest = m.group(2).strip();
                if (!rest.isEmpty() && !rest.startsWith("#")) {
                    throw new TemplateException(FILE, i + 1, "settings must be a block mapping, so the cloud can"
                            + " set restart-on-crash and restart-script");
                }
                if (settings >= 0) {
                    throw new TemplateException(FILE, i + 1, "settings appears twice");
                }
                settings = i;
            } else if (settings < 0 && (line.startsWith("{") || line.startsWith("["))) {
                throw new TemplateException(FILE, i + 1, "the file must be a block mapping, so the cloud can"
                        + " set restart-on-crash and restart-script");
            } else if (endsBlock(stripBom(line, i)) && !isMarker(line)
                    && !SIMPLE_KEY.matcher(stripBom(line, i)).matches()) {
                throw new TemplateException(FILE, i + 1, "top-level keys must be plain or quoted without escapes"
                        + " (no ?, -, anchor, tag or flow), so the cloud can set restart-on-crash and"
                        + " restart-script");
            }
        }

        if (settings < 0) {
            StringBuilder out = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                out.append(eol);
            }
            out.append("settings:").append(eol)
                    .append(DEFAULT_INDENT).append(ON_CRASH_LINE).append(eol)
                    .append(DEFAULT_INDENT).append(SCRIPT_LINE).append(eol);
            return out.toString();
        }

        int end = settings + 1;
        while (end < lines.size() && !endsBlock(lines.get(end))) {
            end++;
        }
        String indent = null;
        for (int i = settings + 1; i < end; i++) {
            if (isContent(lines.get(i))) {
                indent = leading(lines.get(i));
                break;
            }
        }
        if (indent == null) {
            indent = DEFAULT_INDENT;
        }

        boolean onCrash = false;
        boolean script = false;
        for (int i = settings + 1; i < end; i++) {
            String line = lines.get(i);
            if (!isContent(line) || !leading(line).equals(indent)) {
                continue;
            }
            String body = line.substring(indent.length());
            if (!SIMPLE_KEY.matcher(body).matches()) {
                throw new TemplateException(FILE, i + 1, "keys in settings must be plain or quoted without escapes"
                        + " (no ?, -, anchor or tag), so the cloud can set restart-on-crash and restart-script");
            }
            Matcher key = KEY.matcher(body);
            if (!key.matches()) {
                continue;
            }
            // A value that goes on over more lines (block scalar, continued plain scalar) goes with it.
            int last = i;
            for (int j = i + 1; j < end; j++) {
                String next = lines.get(j);
                if (next.isBlank()) {
                    continue;
                }
                if (leading(next).length() > indent.length()) {
                    last = j;
                } else {
                    break;
                }
            }
            for (int j = last; j > i; j--) {
                lines.remove(j);
            }
            end -= last - i;
            boolean isOnCrash = key.group(2).equals(ON_CRASH);
            boolean set = (isOnCrash ? ON_CRASH_SET : SCRIPT_SET).matcher(body).matches();
            if (!set || last > i) {
                lines.set(i, indent + (isOnCrash ? ON_CRASH_LINE : SCRIPT_LINE));
            }
            if (isOnCrash) {
                onCrash = true;
            } else {
                script = true;
            }
        }
        int insert = settings + 1;
        if (!onCrash) {
            lines.add(insert++, indent + ON_CRASH_LINE);
        }
        if (!script) {
            lines.add(insert, indent + SCRIPT_LINE);
        }
        return String.join(eol, lines);
    }

    private static String read(Path target) throws TemplateException, IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readAllBytes();
        }
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new TemplateException(FILE, "is not valid UTF-8");
        }
    }

    /** A line that ends the {@code settings} block: content in the first column, not a comment. */
    private static boolean endsBlock(String line) {
        return !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t' && line.charAt(0) != '#';
    }

    /**
     * A bare document marker or a directive in the first column: {@code ---}, {@code ...},
     * {@code %YAML}. A marker followed by content ({@code --- {settings: ...}}) is none.
     */
    private static boolean isMarker(String line) {
        return MARKER.matcher(line).matches() || line.startsWith("%");
    }

    /** The line without a byte order mark, which only the first line may start with. */
    private static String stripBom(String line, int index) {
        return index == 0 && !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
    }

    private static boolean isContent(String line) {
        String stripped = line.strip();
        return !stripped.isEmpty() && !stripped.startsWith("#");
    }

    private static String leading(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }
}
