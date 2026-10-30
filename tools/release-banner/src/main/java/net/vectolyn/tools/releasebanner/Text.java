package net.vectolyn.tools.releasebanner;

/** Small string helpers that work on code points, as the title rules count characters, not chars. */
final class Text {

    private Text() {
    }

    /** Number of code points. */
    static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** The first {@code n} code points of {@code s} (all of it if it is shorter). */
    static String head(String s, int n) {
        if (length(s) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** The code point at code point index {@code i}. */
    static int codePointAt(String s, int i) {
        return s.codePointAt(s.offsetByCodePoints(0, i));
    }

    /** Drops every trailing character contained in {@code chars} (Python's {@code str.rstrip(chars)}). */
    static String rstrip(String s, String chars) {
        int end = s.length();
        while (end > 0) {
            int cp = s.codePointBefore(end);
            if (chars.indexOf(cp) < 0) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(0, end);
    }

    /** Everything before the last occurrence of {@code sep}, or {@code s} itself if there is none. */
    static String beforeLast(String s, char sep) {
        int i = s.lastIndexOf(sep);
        return i < 0 ? s : s.substring(0, i);
    }

    /** Drops the last code point. */
    static String dropLast(String s) {
        return s.isEmpty() ? s : s.substring(0, s.offsetByCodePoints(s.length(), -1));
    }

    /**
     * True for characters that must never reach the banner: control and format characters
     * (including bidi overrides), line and paragraph separators, unpaired surrogates, private use
     * and unassigned code points.
     */
    static boolean forbidden(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    /** {@code U+00E9} style name of a code point, for error messages. */
    static String codePointName(int cp) {
        return String.format(java.util.Locale.ROOT, "U+%04X", cp);
    }

    /** {@code s} with every forbidden character replaced by its {@code U+XXXX} name, for error messages. */
    static String printable(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (forbidden(cp)) {
                out.append('<').append(codePointName(cp)).append('>');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
