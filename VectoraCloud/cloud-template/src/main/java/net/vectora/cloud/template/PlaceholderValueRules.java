package net.vectora.cloud.template;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a placeholder value may contain, so that no value can change the structure of the file it is
 * inserted into (a new YAML key, a TOML table, an SQL statement).
 *
 * <ul>
 *   <li>No value contains a control character (line breaks, tabs, NUL and the rest of
 *       {@link Character#isISOControl(char)}). The only exceptions are {@value ProxyServerList#SERVERS}
 *       and {@value ProxyServerList#FORCED_HOSTS}, which are built by {@link ProxyServerList} and may
 *       span lines ({@code \n}, {@code \r}); every other control character is refused there too.</li>
 *   <li>Secrets (names ending in {@code _SECRET} or {@code _PASSWORD}) consist of at least
 *       {@value #SECRET_MIN_LENGTH} characters from {@code A-Z a-z 0-9 . _ ~ + / = -}. That covers
 *       alphanumeric and base64 secrets and keeps quotes, {@code #}, spaces and backslashes out.</li>
 *   <li>Ports (names ending in {@code _PORT}) are decimal numbers from 1 to 65535.</li>
 * </ul>
 *
 * <p>Error messages name the placeholder, never the value.
 */
final class PlaceholderValueRules {

    /** Shortest accepted secret. */
    static final int SECRET_MIN_LENGTH = 16;

    private static final Set<String> MULTI_LINE = Set.of(ProxyServerList.SERVERS, ProxyServerList.FORCED_HOSTS);
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9._~+/=-]{" + SECRET_MIN_LENGTH + ",}");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private PlaceholderValueRules() {
    }

    /**
     * Checks a value before it is inserted.
     *
     * @param name placeholder name without braces
     * @param value its value
     * @param file file used in the error message
     * @param line line used in the error message, or {@link TemplateException#NO_LINE}
     * @throws TemplateException if the value breaks one of the rules
     */
    static void check(String name, String value, String file, int line) throws TemplateException {
        boolean multiLine = MULTI_LINE.contains(name);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(multiLine && (c == '\n' || c == '\r'))) {
                throw new TemplateException(file, line, "value of {{" + name + "}} contains a line break or control "
                        + "character; only " + ProxyServerList.SERVERS + " and " + ProxyServerList.FORCED_HOSTS
                        + " may span lines");
            }
        }
        if (isSecret(name)) {
            checkSecret(name, value, file, line);
        }
        if (name.endsWith("_PORT") && !isPort(value)) {
            throw new TemplateException(file, line, "value of {{" + name + "}} is not a port from 1 to 65535");
        }
    }

    /**
     * Checks a secret.
     *
     * @param name placeholder name without braces
     * @param value the secret
     * @param file file used in the error message
     * @param line line used in the error message, or {@link TemplateException#NO_LINE}
     * @throws TemplateException if the secret is too short or contains other characters
     */
    static void checkSecret(String name, String value, String file, int line) throws TemplateException {
        if (!SECRET.matcher(value).matches()) {
            throw new TemplateException(file, line, "value of {{" + name + "}} must be at least " + SECRET_MIN_LENGTH
                    + " characters from A-Z, a-z, 0-9 and . _ ~ + / = -");
        }
    }

    static boolean isSecret(String name) {
        return name.endsWith("_SECRET") || name.endsWith("_PASSWORD");
    }

    private static boolean isPort(String value) {
        if (!PORT.matcher(value).matches()) {
            return false;
        }
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535;
    }
}
