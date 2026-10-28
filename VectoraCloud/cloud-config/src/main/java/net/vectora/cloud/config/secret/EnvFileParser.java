package net.vectora.cloud.config.secret;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectora.cloud.config.secret.EnvFormatException.Reason;

/**
 * Strict parser for {@code KEY=VALUE} files.
 *
 * <ul>
 *   <li>UTF-8; a leading byte order mark is skipped. Line endings {@code \n} or {@code \r\n}.</li>
 *   <li>A line is blank (only spaces and tabs), a comment ({@code #} after optional spaces or
 *       tabs) or {@code KEY=VALUE}.</li>
 *   <li>Keys match {@code [A-Z0-9_]+}, at most 128 characters, and appear once.</li>
 *   <li>The value is everything after the first {@code =}, taken literally: no quotes, no escapes,
 *       no {@code export}, no inline comments, no variable expansion. It may be empty.</li>
 *   <li>No multi-line values: control characters and Unicode line separators are rejected
 *       everywhere. A value must not start or end with whitespace.</li>
 *   <li>At most {@link #MAX_BYTES} bytes and {@link #MAX_LINES} lines.</li>
 * </ul>
 */
public final class EnvFileParser {

    /** Upper limit for the file size. */
    public static final int MAX_BYTES = 64 * 1024;
    /** Upper limit for the number of lines. */
    public static final int MAX_LINES = 2000;
    /** Upper limit for the key length. */
    public static final int MAX_KEY_LENGTH = 128;
    /** Key pattern. */
    public static final String KEY_PATTERN = "[A-Z0-9_]+";

    private static final Pattern KEY = Pattern.compile(KEY_PATTERN);

    private EnvFileParser() {
    }

    /** Parses raw bytes. */
    public static EnvFile parse(byte[] content) throws EnvFormatException {
        if (content.length > MAX_BYTES) {
            throw new EnvFormatException(0, Reason.TOO_LARGE);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new EnvFormatException(0, Reason.INVALID_ENCODING);
        }
        return parse(text);
    }

    /** Parses text. */
    public static EnvFile parse(String content) throws EnvFormatException {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        if (text.isEmpty()) {
            return EnvFile.empty();
        }
        String[] raw = text.split("\n", -1);
        int count = raw[raw.length - 1].isEmpty() ? raw.length - 1 : raw.length;
        if (count > MAX_LINES) {
            throw new EnvFormatException(0, Reason.TOO_MANY_LINES);
        }
        List<EnvFile.Line> lines = new ArrayList<>(count);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int number = i + 1;
            String line = raw[i].endsWith("\r") ? raw[i].substring(0, raw[i].length() - 1) : raw[i];
            if (hasControlCharacter(line)) {
                throw new EnvFormatException(number, Reason.CONTROL_CHARACTER);
            }
            String trimmed = stripLeadingBlanks(line);
            if (trimmed.isEmpty()) {
                lines.add(new EnvFile.Blank(line));
            } else if (trimmed.charAt(0) == '#') {
                lines.add(new EnvFile.Comment(line));
            } else {
                int separator = line.indexOf('=');
                if (separator < 0) {
                    throw new EnvFormatException(number, Reason.MISSING_SEPARATOR);
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!isValidKey(key)) {
                    throw new EnvFormatException(number, Reason.INVALID_KEY);
                }
                if (hasSurroundingWhitespace(value)) {
                    throw new EnvFormatException(number, Reason.SURROUNDING_WHITESPACE);
                }
                if (!keys.add(key)) {
                    throw new EnvFormatException(number, Reason.DUPLICATE_KEY);
                }
                lines.add(new EnvFile.Entry(key, Secret.of(value)));
            }
        }
        return EnvFile.of(lines);
    }

    /** Whether {@code key} is a valid key. */
    public static boolean isValidKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && KEY.matcher(key).matches();
    }

    /** Throws if {@code key} is not a valid key. The message does not contain the key. */
    static void checkKey(String key) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("key must match " + KEY_PATTERN);
        }
    }

    /** Throws if {@code value} could not be written as one line. The message does not contain the value. */
    static void checkValue(String value) {
        if (hasControlCharacter(value) || hasSurroundingWhitespace(value)) {
            throw new IllegalArgumentException("value must be a single line without surrounding whitespace");
        }
    }

    private static boolean hasControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowedTab = c == '\t';
            if ((c < 0x20 && !allowedTab) || c == 0x7F || c == 0x85 || c == ' ' || c == ' ') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSurroundingWhitespace(String value) {
        return !value.isEmpty()
                && (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1))
                || Character.isSpaceChar(value.charAt(0)) || Character.isSpaceChar(value.charAt(value.length() - 1)));
    }

    private static String stripLeadingBlanks(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(i);
    }
}
