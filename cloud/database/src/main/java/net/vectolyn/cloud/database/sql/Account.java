package net.vectolyn.cloud.database.sql;

import java.util.Objects;

/**
 * A database account as the server lists it in {@code mysql.user}.
 *
 * @param user user name, empty for an anonymous account
 * @param host host part
 */
public record Account(String user, String host) {

    /**
     * Checks the values.
     *
     * @param user the user name
     * @param host the host part
     */
    public Account {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(host, "host");
    }

    /**
     * Tells whether this is an anonymous account ({@code ''@host}).
     *
     * @return whether the user name is empty
     */
    public boolean anonymous() {
        return user.isEmpty();
    }
}
