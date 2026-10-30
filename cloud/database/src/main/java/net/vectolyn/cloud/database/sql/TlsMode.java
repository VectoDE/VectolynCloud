package net.vectolyn.cloud.database.sql;

/**
 * TLS setting of a connection, passed to MariaDB Connector/J as {@code sslMode}.
 *
 * <p>Connector/J 4 verifies TLS fully by default. The cloud keeps that for every server reached over
 * the network and switches TLS off only for loopback connections, where encryption protects
 * nothing.
 */
public enum TlsMode {

    /** No TLS. Only allowed for loopback addresses. */
    DISABLE("disable"),
    /** TLS with certificate and host name verification (the Connector/J 4 default). */
    VERIFY_FULL("verify-full");

    private final String sslMode;

    TlsMode(String sslMode) {
        this.sslMode = sslMode;
    }

    /**
     * Returns the value of the Connector/J option {@code sslMode}.
     *
     * @return the option value
     */
    public String sslMode() {
        return sslMode;
    }
}
