package net.vectolyn.cloud.database.sql;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds the identifiers and string literals of the setup SQL. The setup statements (database,
 * user, grants) cannot use bind parameters, so every name and value goes through this class.
 *
 * <ul>
 *   <li>Database names and user names are checked against a strict allowlist before they are
 *       quoted, so a bad name fails instead of being escaped into something unexpected.</li>
 *   <li>String literals are quoted with {@code '} and inner quotes are doubled. Backslashes and
 *       control characters are refused: their meaning depends on the server's {@code sql_mode}
 *       ({@code NO_BACKSLASH_ESCAPES}), so the only safe choice is to never send them.</li>
 * </ul>
 *
 * <p>Error messages never contain the rejected value, because passwords pass through here too.
 */
public final class SqlQuoting {

    /** Database name and user name rule, the same as {@code database.name} in {@code cloud.yml}. */
    public static final String NAME_PATTERN = "[A-Za-z0-9_]{1,64}";

    /**
     * Host part of an account: {@code %}, {@code localhost}, an IPv4 or IPv6 address, or a host
     * name. Wildcards other than a lone {@code %} are not accepted.
     */
    public static final String HOST_PATTERN =
            "%|[0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9A-Fa-f:]{2,39}|[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?";

    private static final Pattern NAME = Pattern.compile(NAME_PATTERN);
    private static final Pattern HOST = Pattern.compile(HOST_PATTERN);

    /** Longest literal accepted; far above every real password or host. */
    static final int MAX_LITERAL_LENGTH = 1024;

    private SqlQuoting() {
    }

    /**
     * Tells whether {@code name} is a valid database or user name.
     *
     * @param name the name
     * @return whether it matches {@link #NAME_PATTERN}
     */
    public static boolean isName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * Tells whether {@code host} is a valid host part.
     *
     * @param host the host
     * @return whether it matches {@link #HOST_PATTERN}
     */
    public static boolean isHost(String host) {
        return host != null && HOST.matcher(host).matches();
    }

    /**
     * Quotes a database name as identifier.
     *
     * @param name the database name
     * @return {@code `name`}
     * @throws IllegalArgumentException if the name breaks {@link #NAME_PATTERN}
     */
    public static String identifier(String name) {
        if (!isName(name)) {
            throw new IllegalArgumentException("database name must match " + NAME_PATTERN);
        }
        // The allowlist has no backtick; doubling is kept so the method stays correct on its own.
        return '`' + name.replace("`", "``") + '`';
    }

    /**
     * Quotes a text as string literal.
     *
     * @param value the text, for example a password
     * @return {@code 'value'} with inner quotes doubled
     * @throws IllegalArgumentException if the text contains a backslash or a control character or is
     *     longer than 1024 characters
     */
    public static String literal(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_LITERAL_LENGTH) {
            throw new IllegalArgumentException("SQL literal is longer than " + MAX_LITERAL_LENGTH + " characters");
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("SQL literal contains a backslash or a control character");
            }
            if (c == '\'') {
                out.append('\'');
            }
            out.append(c);
        }
        return out.append('\'').toString();
    }

    /**
     * Quotes an account that the cloud creates or changes ({@code 'user'@'host'}).
     *
     * @param user user name, must match {@link #NAME_PATTERN}
     * @param host host part, must match {@link #HOST_PATTERN}
     * @return the quoted account
     * @throws IllegalArgumentException if a part breaks its rule
     */
    public static String account(String user, String host) {
        if (!isName(user)) {
            throw new IllegalArgumentException("user name must match " + NAME_PATTERN);
        }
        if (!isHost(host)) {
            throw new IllegalArgumentException("account host must be %, an IP address or a host name");
        }
        return literal(user) + '@' + literal(host);
    }

    /**
     * Quotes an account that the server reported, for example an anonymous user to drop. The user
     * may be empty and the host may contain wildcards, but both still pass {@link #literal}.
     *
     * @param user user name as reported by the server
     * @param host host part as reported by the server
     * @return the quoted account
     * @throws IllegalArgumentException if a part contains a backslash or a control character
     */
    public static String existingAccount(String user, String host) {
        return literal(user) + '@' + literal(host);
    }
}
