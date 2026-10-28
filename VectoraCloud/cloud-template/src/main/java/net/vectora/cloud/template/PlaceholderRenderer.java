package net.vectora.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict renderer for {@code {{VECTORA_*}}} placeholders in one text.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code {{NAME}}} is replaced by the value of {@code NAME}. {@code NAME} is
 *       {@code VECTORA_} followed by upper-case letters, digits and underscores, with no spaces.</li>
 *   <li>A name without a value is an error, never an empty string.</li>
 *   <li>Every other <code>&#123;&#123;</code> is an error too (unclosed, lower case, spaces, foreign names), so a
 *       rendered file never contains a leftover placeholder.</li>
 *   <li>Escape: four opening braces <code>&#123;&#123;&#123;&#123;</code> stand for a literal <code>&#123;&#123;</code>. To write
 *       {@code {{VECTORA_X}}} literally, write <code>&#123;&#123;&#123;&#123;VECTORA_X&#125;&#125;</code>. A single <code>&#123;</code> and
 *       <code>&#125;&#125;</code> need no escape.</li>
 *   <li>Everything outside placeholders is kept as it is, including line endings and a byte order
 *       mark. Line breaks inside a value ({@code \n}, {@code \r\n} or {@code \r}) are written with
 *       the line ending the file uses (its first line break; {@code \n} if it has none).</li>
 *   <li>Values are inserted once and are not scanned for placeholders again.</li>
 * </ul>
 *
 * <p>The renderer runs in time linear to the size of the text plus the inserted values.
 */
public final class PlaceholderRenderer {

    /** Start of every placeholder name. */
    public static final String NAME_PREFIX = "VECTORA_";

    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";
    private static final String ESCAPE = "{{{{";
    /** Longest part of a broken placeholder that is quoted in an error message. */
    private static final int MAX_QUOTE = 40;

    private PlaceholderRenderer() {
    }

    /**
     * Renders the placeholders in {@code text}.
     *
     * @param text template text
     * @param file file name used in error messages, relative to the template folder
     * @param values placeholder values
     * @return the rendered text; {@code text} itself if it contains no <code>&#123;&#123;</code>
     * @throws TemplateException if a placeholder is unknown, has no value or is malformed
     */
    public static String render(String text, String file, PlaceholderValues values) throws TemplateException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(values, "values");
        int open = text.indexOf(OPEN);
        if (open < 0) {
            return text;
        }
        String lineEnding = lineEnding(text);
        StringBuilder out = new StringBuilder(text.length() + 64);
        int from = 0;
        while (open >= 0) {
            out.append(text, from, open);
            if (text.startsWith(ESCAPE, open)) {
                out.append(OPEN);
                from = open + ESCAPE.length();
            } else {
                int nameStart = open + OPEN.length();
                int nameEnd = nameStart;
                while (nameEnd < text.length() && isNameChar(text.charAt(nameEnd))) {
                    nameEnd++;
                }
                String name = text.substring(nameStart, nameEnd);
                if (!text.startsWith(CLOSE, nameEnd) || !isValidName(name)) {
                    throw new TemplateException(file, lineOf(text, open),
                            "malformed placeholder " + quote(text, open)
                                    + "; expected {{" + NAME_PREFIX + "NAME}} or {{{{ for a literal {{");
                }
                Optional<String> value = values.find(name);
                if (value.isEmpty()) {
                    throw new TemplateException(file, lineOf(text, open),
                            "unknown placeholder {{" + name + "}} (no value given)");
                }
                appendWithLineEnding(out, value.get(), lineEnding);
                from = nameEnd + CLOSE.length();
            }
            open = text.indexOf(OPEN, from);
        }
        out.append(text, from, text.length());
        return out.toString();
    }

    /**
     * Renders the placeholders in UTF-8 encoded {@code content}.
     *
     * @param content template file content, UTF-8 encoded (a byte order mark is kept)
     * @param file file name used in error messages, relative to the template folder
     * @param values placeholder values
     * @return the rendered content, UTF-8 encoded; equal to {@code content} if it contains no
     *     placeholder
     * @throws TemplateException if the content is not valid UTF-8, a value cannot be encoded, or a
     *     placeholder is unknown, has no value or is malformed
     */
    public static byte[] renderUtf8(byte[] content, String file, PlaceholderValues values) throws TemplateException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(file, "file");
        String text = decode(content, file);
        String rendered = render(text, file, values);
        if (rendered == text) {
            return content.clone();
        }
        CharsetEncoder encoder = UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(rendered));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            // The template decoded cleanly, so the broken text must come from a value.
            throw new TemplateException(file, "a placeholder value is not valid Unicode text");
        }
    }

    private static String decode(byte[] content, String file) throws TemplateException {
        CharsetDecoder decoder = UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(content);
        CharBuffer out = CharBuffer.allocate((int) Math.ceil(content.length * (double) decoder.maxCharsPerByte()));
        CoderResult result = decoder.decode(in, out, true);
        if (result.isUnderflow()) {
            result = decoder.flush(out);
        }
        if (result.isError()) {
            throw new TemplateException(file, lineOf(content, in.position()), "not valid UTF-8");
        }
        return out.flip().toString();
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isValidName(String name) {
        return name.length() > NAME_PREFIX.length() && name.startsWith(NAME_PREFIX);
    }

    private static String lineEnding(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return "\n";
            }
            if (c == '\r') {
                return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
            }
        }
        return "\n";
    }

    private static void appendWithLineEnding(StringBuilder out, String value, String lineEnding) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r') {
                out.append(lineEnding);
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                out.append(lineEnding);
            } else {
                out.append(c);
            }
        }
    }

    /** One-based line of {@code index}; {@code \n}, {@code \r\n} and a lone {@code \r} end a line. */
    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                line++;
            }
        }
        return line;
    }

    private static int lineOf(byte[] content, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            byte b = content[i];
            if (b == '\n' || (b == '\r' && (i + 1 >= content.length || content[i + 1] != '\n'))) {
                line++;
            }
        }
        return line;
    }

    private static String quote(String text, int start) {
        int end = Math.min(text.length(), start + MAX_QUOTE);
        int lineBreak = indexOfLineBreak(text, start, end);
        String part = text.substring(start, lineBreak);
        StringBuilder printable = new StringBuilder(part.length() + 2).append('"');
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            printable.append(Character.isISOControl(c) || Character.isSurrogate(c) ? '?' : c);
        }
        if (lineBreak == end && end < text.length()) {
            printable.append("...");
        }
        return printable.append('"').toString();
    }

    private static int indexOfLineBreak(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return end;
    }
}
