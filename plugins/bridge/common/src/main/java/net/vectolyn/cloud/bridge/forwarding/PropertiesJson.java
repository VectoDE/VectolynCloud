package net.vectolyn.cloud.bridge.forwarding;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the properties part of a forwarded handshake: a JSON array of objects with the
 * string members {@code name}, {@code value} and optionally {@code signature} (which may also be
 * {@code null}). That is exactly what Velocity writes.
 *
 * <p>Deliberately a small strict parser instead of a JSON library: the bridge must not bring a
 * library onto the server's class path (see docs/cloud/architecture.md), and the input comes from
 * whoever can reach the backend port. Anything else (other members, numbers, nesting, duplicate
 * members, raw control characters, trailing data) is rejected. Limits: {@value #MAX_PROPERTIES}
 * properties and the length limit of the handshake itself.
 */
public final class PropertiesJson {

    /** Most properties accepted. Velocity forwards one or two (skin, token). */
    public static final int MAX_PROPERTIES = 32;

    private final String in;
    private int pos;

    private PropertiesJson(String in) {
        this.in = in;
    }

    /**
     * Parses a properties array.
     *
     * @throws MalformedHandshakeException if the text is not such an array
     */
    public static List<GameProperty> parse(String json) throws MalformedHandshakeException {
        PropertiesJson parser = new PropertiesJson(json);
        List<GameProperty> result = parser.array();
        parser.whitespace();
        if (parser.pos != json.length()) {
            throw new MalformedHandshakeException("data after the properties array");
        }
        return result;
    }

    /** Writes properties as a JSON array that {@link #parse} and Gson read back unchanged. */
    public static String write(List<GameProperty> properties) {
        StringBuilder out = new StringBuilder(64 * properties.size() + 2).append('[');
        for (int i = 0; i < properties.size(); i++) {
            GameProperty property = properties.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"name\":");
            string(out, property.name());
            out.append(",\"value\":");
            string(out, property.value());
            if (property.signature() != null) {
                out.append(",\"signature\":");
                string(out, property.signature());
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    private List<GameProperty> array() throws MalformedHandshakeException {
        whitespace();
        expect('[');
        List<GameProperty> result = new ArrayList<>();
        whitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            if (result.size() == MAX_PROPERTIES) {
                throw new MalformedHandshakeException("more than " + MAX_PROPERTIES + " properties");
            }
            result.add(object());
            whitespace();
            int next = next();
            if (next == ']') {
                return result;
            }
            if (next != ',') {
                throw new MalformedHandshakeException("expected ',' or ']' in the properties array");
            }
            whitespace();
        }
    }

    private GameProperty object() throws MalformedHandshakeException {
        expect('{');
        String name = null;
        String value = null;
        String signature = null;
        boolean hasSignature = false;
        whitespace();
        if (peek() == '}') {
            throw new MalformedHandshakeException("property without name and value");
        }
        while (true) {
            whitespace();
            String key = string();
            whitespace();
            expect(':');
            whitespace();
            String member = peek() == 'n' ? nullLiteral() : string();
            switch (key) {
                case "name" -> {
                    if (name != null) {
                        throw new MalformedHandshakeException("duplicate member name");
                    }
                    name = require(member, key);
                }
                case "value" -> {
                    if (value != null) {
                        throw new MalformedHandshakeException("duplicate member value");
                    }
                    value = require(member, key);
                }
                case "signature" -> {
                    if (hasSignature) {
                        throw new MalformedHandshakeException("duplicate member signature");
                    }
                    hasSignature = true;
                    signature = member;
                }
                default -> throw new MalformedHandshakeException("unknown property member");
            }
            whitespace();
            int next = next();
            if (next == '}') {
                break;
            }
            if (next != ',') {
                throw new MalformedHandshakeException("expected ',' or '}' in a property");
            }
        }
        if (name == null || value == null) {
            throw new MalformedHandshakeException("property without name or value");
        }
        return new GameProperty(name, value, signature);
    }

    private static String require(String member, String key) throws MalformedHandshakeException {
        if (member == null) {
            throw new MalformedHandshakeException("member " + key + " is null");
        }
        return member;
    }

    private String nullLiteral() throws MalformedHandshakeException {
        if (!in.startsWith("null", pos)) {
            throw new MalformedHandshakeException("expected a string or null");
        }
        pos += 4;
        return null;
    }

    private String string() throws MalformedHandshakeException {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                // Includes the end of input (-1).
                throw new MalformedHandshakeException(c < 0 ? "unterminated string" : "control character in a string");
            }
            if (c != '\\') {
                out.append((char) c);
                continue;
            }
            int escaped = next();
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw new MalformedHandshakeException("invalid escape in a string");
            }
        }
    }

    private char unicode() throws MalformedHandshakeException {
        if (pos + 4 > in.length()) {
            throw new MalformedHandshakeException("short unicode escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(in.charAt(pos + i), 16);
            if (digit < 0 || in.charAt(pos + i) > 'f') {
                throw new MalformedHandshakeException("invalid unicode escape");
            }
            value = value * 16 + digit;
        }
        pos += 4;
        return (char) value;
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        // Surrogates as escapes, so a lone one survives the round trip.
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void whitespace() {
        while (pos < in.length()) {
            char c = in.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private int peek() {
        return pos < in.length() ? in.charAt(pos) : -1;
    }

    private int next() {
        return pos < in.length() ? in.charAt(pos++) : -1;
    }

    private void expect(char c) throws MalformedHandshakeException {
        if (next() != c) {
            throw new MalformedHandshakeException("expected '" + c + "'");
        }
    }
}
