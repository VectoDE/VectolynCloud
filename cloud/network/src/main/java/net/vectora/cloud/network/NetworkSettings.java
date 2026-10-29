package net.vectora.cloud.network;

import java.time.Duration;
import java.util.Objects;
import net.vectora.cloud.config.model.NodeConfig;

/**
 * Settings of the cloud channel listener.
 *
 * <p>The address comes from the node's entry in {@code nodes.yml} ({@code host} and {@code port},
 * default {@code 127.0.0.1:7010}), so out of the box the channel is reachable from this machine
 * only. The limits and timeouts have fixed defaults; see {@code docs/cloud/network.md}.
 *
 * @param bindHost host name or IP literal the server listens on
 * @param port TCP port, 0 for any free port (tests), otherwise 1 to 65535
 * @param handshakeTimeout time a new connection has for {@code Hello}, counted from the accept,
 *     so a peer that sends one byte at a time (slowloris) is closed all the same
 * @param idleTimeout an authenticated connection that sends nothing for this long is closed
 * @param pingInterval the server sends {@code Ping} when it has written nothing for this long;
 *     shorter than {@code idleTimeout}
 * @param maxConnections open connections in total, authenticated or not
 * @param maxConnectionsPerAddress open connections from one IP address. On a single node every
 *     service connects from the loopback address, so this is high by default
 * @param maxPendingRequests requests a connection may have open at a time, in each direction
 * @param requestTimeout how long the cloud waits for the response to its own requests
 */
public record NetworkSettings(String bindHost, int port, Duration handshakeTimeout, Duration idleTimeout,
        Duration pingInterval, int maxConnections, int maxConnectionsPerAddress, int maxPendingRequests,
        Duration requestTimeout) {

    /** Default handshake timeout: 10 seconds. */
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

    /** Default idle timeout: 30 seconds. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /** Default ping interval: 10 seconds. */
    public static final Duration DEFAULT_PING_INTERVAL = Duration.ofSeconds(10);

    /** Default limit of open connections in total. */
    public static final int DEFAULT_MAX_CONNECTIONS = 1024;

    /** Default limit of open connections per IP address. */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ADDRESS = 256;

    /** Default limit of open requests per connection and direction. */
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 256;

    /** Default request timeout: 10 seconds. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Checks the settings.
     *
     * @throws IllegalArgumentException if a value is out of range or the ping interval is not
     *     shorter than the idle timeout
     */
    public NetworkSettings {
        Objects.requireNonNull(bindHost, "bindHost");
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bind host must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be 0 to 65535, got " + port);
        }
        positive(handshakeTimeout, "handshake timeout");
        positive(idleTimeout, "idle timeout");
        positive(pingInterval, "ping interval");
        positive(requestTimeout, "request timeout");
        if (pingInterval.compareTo(idleTimeout) >= 0) {
            throw new IllegalArgumentException("ping interval must be shorter than the idle timeout");
        }
        if (maxConnections < 1 || maxConnectionsPerAddress < 1 || maxPendingRequests < 1) {
            throw new IllegalArgumentException("connection and request limits must be at least 1");
        }
    }

    /** Listens on {@code 127.0.0.1:7010} with the default limits. */
    public static NetworkSettings defaults() {
        return forNode(NodeConfig.defaults());
    }

    /** Listens on the host and port of {@code node} with the default limits. */
    public static NetworkSettings forNode(NodeConfig node) {
        Objects.requireNonNull(node, "node");
        return new NetworkSettings(node.host(), node.port(), DEFAULT_HANDSHAKE_TIMEOUT, DEFAULT_IDLE_TIMEOUT,
                DEFAULT_PING_INTERVAL, DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS_PER_ADDRESS,
                DEFAULT_MAX_PENDING_REQUESTS, DEFAULT_REQUEST_TIMEOUT);
    }

    /** A copy that listens on another address. */
    public NetworkSettings withAddress(String host, int newPort) {
        return new NetworkSettings(host, newPort, handshakeTimeout, idleTimeout, pingInterval, maxConnections,
                maxConnectionsPerAddress, maxPendingRequests, requestTimeout);
    }

    /** A copy with other timeouts. */
    public NetworkSettings withTimeouts(Duration handshake, Duration idle, Duration ping, Duration request) {
        return new NetworkSettings(bindHost, port, handshake, idle, ping, maxConnections, maxConnectionsPerAddress,
                maxPendingRequests, request);
    }

    /** A copy with other limits. */
    public NetworkSettings withLimits(int total, int perAddress, int pendingRequests) {
        return new NetworkSettings(bindHost, port, handshakeTimeout, idleTimeout, pingInterval, total, perAddress,
                pendingRequests, requestTimeout);
    }

    private static void positive(Duration value, String what) {
        Objects.requireNonNull(value, what);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(what + " must be positive");
        }
    }
}
