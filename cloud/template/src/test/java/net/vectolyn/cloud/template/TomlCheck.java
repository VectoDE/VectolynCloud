package net.vectolyn.cloud.template;

import java.util.HashSet;
import java.util.Set;

/**
 * Plausibility check for the TOML subset the templates use (no TOML library in the build).
 *
 * <p>Accepts comments, blank lines, {@code [table]} headers and one-line {@code key = value} pairs
 * whose value is a string, number, boolean or one-line array of those. Rejects duplicate keys and
 * tables, unterminated strings, unbalanced arrays and anything it does not understand.
 */
final class TomlCheck {

    private TomlCheck() {
    }

    /**
     * Checks {@code toml}.
     *
     * @param toml file content
     * @return null if plausible, else the first problem with its line
     */
    static String problem(String toml) {
        Set<String> tables = new HashSet<>();
        Set<String> keys = new HashSet<>();
        String table = "";
        String[] lines = toml.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            String where = "line " + (i + 1) + ": ";
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                String header = stripComment(line);
                if (!header.matches("\\[[A-Za-z0-9_.-]+]")) {
                    return where + "bad table header";
                }
                table = header.substring(1, header.length() - 1);
                if (!tables.add(table)) {
                    return where + "duplicate table " + table;
                }
                continue;
            }
            int eq = keyEnd(line);
            if (eq < 0) {
                return where + "no key = value";
            }
            String key = line.substring(0, eq).strip();
            if (!key.matches("[A-Za-z0-9_-]+|\"[^\"\\\\]*\"")) {
                return where + "bad key " + key;
            }
            if (!keys.add(table + "\u0000" + key)) {
                return where + "duplicate key " + key;
            }
            String rest = line.substring(eq + 1).strip();
            int end = value(rest, 0);
            if (end < 0) {
                return where + "bad value";
            }
            String tail = rest.substring(end).strip();
            if (!tail.isEmpty() && !tail.startsWith("#")) {
                return where + "text after value";
            }
        }
        return null;
    }

    private static int keyEnd(String line) {
        if (line.startsWith("\"")) {
            int close = line.indexOf('"', 1);
            return close < 0 ? -1 : line.indexOf('=', close);
        }
        return line.indexOf('=');
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return (hash < 0 ? line : line.substring(0, hash)).strip();
    }

    /** Parses one value starting at {@code i}; returns the index after it or -1. */
    private static int value(String s, int i) {
        if (i >= s.length()) {
            return -1;
        }
        char c = s.charAt(i);
        if (c == '"') {
            for (int j = i + 1; j < s.length(); j++) {
                char d = s.charAt(j);
                if (d == '\\') {
                    j++;
                } else if (d == '"') {
                    return j + 1;
                }
            }
            return -1;
        }
        if (c == '\'') {
            int close = s.indexOf('\'', i + 1);
            return close < 0 ? -1 : close + 1;
        }
        if (c == '[') {
            int j = skipSpaces(s, i + 1);
            if (j < s.length() && s.charAt(j) == ']') {
                return j + 1;
            }
            while (true) {
                j = value(s, j);
                if (j < 0) {
                    return -1;
                }
                j = skipSpaces(s, j);
                if (j >= s.length()) {
                    return -1;
                }
                if (s.charAt(j) == ']') {
                    return j + 1;
                }
                if (s.charAt(j) != ',') {
                    return -1;
                }
                j = skipSpaces(s, j + 1);
                if (j < s.length() && s.charAt(j) == ']') {
                    return j + 1;
                }
            }
        }
        int j = i;
        while (j < s.length() && "+-0123456789._eExabcdfotrulsn".indexOf(s.charAt(j)) >= 0) {
            j++;
        }
        String scalar = s.substring(i, j);
        if (scalar.equals("true") || scalar.equals("false")
                || scalar.matches("[+-]?(0|[1-9][0-9_]*)(\\.[0-9_]+)?([eE][+-]?[0-9]+)?")) {
            return j;
        }
        return -1;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }
}
