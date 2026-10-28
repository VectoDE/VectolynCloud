package net.vectora.cloud.template;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A backend server as the proxy sees it.
 *
 * <p>The values are checked here because they end up unquoted or inside quotes in
 * {@code velocity.toml}: the name must be a bare TOML key, the host must not contain quotes or
 * spaces.
 *
 * @param name server name in Velocity, e.g. {@code lobby}: 1 to 64 letters, digits, {@code _}, {@code -}
 * @param host address the proxy connects to: host name, IPv4 address or IPv6 address (with or
 *     without brackets)
 * @param port port the backend listens on, 1 to 65535
 * @param wanted whether the operator wants this server; unwanted servers appear nowhere in the
 *     proxy configuration
 */
public record ProxyBackend(String name, String host, int port, boolean wanted) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[?[0-9A-Fa-f:.]{2,45}]?");

    /**
     * Checks the values.
     *
     * @param name server name in Velocity
     * @param host address the proxy connects to
     * @param port port the backend listens on
     * @param wanted whether the operator wants this server
     * @throws IllegalArgumentException if a value cannot be written safely into {@code velocity.toml}
     */
    public ProxyBackend {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("server name must be 1 to 64 letters, digits, _ or -: " + name);
        }
        if (!HOST.matcher(host).matches() || host.startsWith("[") != host.endsWith("]")) {
            throw new IllegalArgumentException("invalid host for server " + name);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range for server " + name + ": " + port);
        }
    }

    /**
     * Returns the address in the form Velocity expects, {@code host:port} with IPv6 addresses in
     * brackets.
     *
     * @return the address
     */
    public String address() {
        boolean ipv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        return (ipv6 ? "[" + host + "]" : host) + ":" + port;
    }
}
