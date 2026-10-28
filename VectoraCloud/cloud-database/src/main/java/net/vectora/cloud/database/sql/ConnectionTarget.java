package net.vectora.cloud.database.sql;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectora.cloud.config.validation.ConfigNames;

/**
 * Where a database server is reached.
 *
 * @param host host name or IP address, IPv6 with or without brackets
 * @param port TCP port
 * @param tls TLS setting; {@link TlsMode#DISABLE} only for loopback addresses
 * @param timeout connect and socket timeout
 */
public record ConnectionTarget(String host, int port, TlsMode tls, Duration timeout) {

    /** Default connect and socket timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern HOST = Pattern.compile(ConfigNames.HOST);

    /**
     * Checks the values.
     *
     * @param host the host
     * @param port the port
     * @param tls the TLS setting
     * @param timeout the timeout
     */
    public ConnectionTarget {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(timeout, "timeout");
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("database host is not a host name or IP address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("database port must be between 1 and 65535");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (tls == TlsMode.DISABLE && !ConfigNames.isLoopback(host)) {
            throw new IllegalArgumentException("TLS may only be switched off for loopback addresses");
        }
    }

    /**
     * A target with the TLS setting the cloud uses by default: off for loopback, fully verified
     * otherwise.
     *
     * @param host the host
     * @param port the port
     * @return the target
     */
    public static ConnectionTarget of(String host, int port) {
        return new ConnectionTarget(host, port, ConfigNames.isLoopback(host) ? TlsMode.DISABLE : TlsMode.VERIFY_FULL,
                DEFAULT_TIMEOUT);
    }

    /**
     * Returns a copy with another timeout.
     *
     * @param value the timeout
     * @return the copy
     */
    public ConnectionTarget withTimeout(Duration value) {
        return new ConnectionTarget(host, port, tls, value);
    }

    /**
     * Returns the JDBC URL without database, user or password. Those go into the connection
     * properties, so they never appear in a URL that ends up in a log.
     *
     * @return {@code jdbc:mariadb://host:port/}
     */
    public String jdbcUrl() {
        String h = host;
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        return "jdbc:mariadb://" + h + ":" + port + "/";
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
