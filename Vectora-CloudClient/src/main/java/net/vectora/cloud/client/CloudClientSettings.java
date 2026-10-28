package net.vectora.cloud.client;

import java.time.Duration;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ServiceId;

/**
 * Where the client connects to and who it is.
 *
 * <p>The cloud starts every service with four environment variables, see
 * {@link #fromEnvironment()}. The timeouts have defaults that fit the cloud's defaults (ping every
 * 10 s, idle timeout 30 s). {@link #toString()} does not show the token.
 *
 * @param host host of the cloud channel
 * @param port port of the cloud channel, 1 to 65535
 * @param service id of this service
 * @param token per-start token of this service
 * @param connectTimeout time for the TCP connect and for the cloud's {@code AuthResult}
 * @param pingInterval the client sends {@code Ping} this often while connected
 * @param idleTimeout the connection counts as dead when nothing arrives for this long; longer
 *     than the cloud's ping interval
 * @param requestTimeout how long {@link CloudClient#request} waits for the response
 * @param minBackoff first wait before a reconnect
 * @param maxBackoff longest wait between reconnects; the wait doubles up to this value
 */
public record CloudClientSettings(String host, int port, ServiceId service, AuthToken token, Duration connectTimeout,
        Duration pingInterval, Duration idleTimeout, Duration requestTimeout, Duration minBackoff,
        Duration maxBackoff) {

    /** Environment variable with the host of the cloud channel. */
    public static final String ENV_HOST = "VECTORA_CLOUD_HOST";

    /** Environment variable with the port of the cloud channel. */
    public static final String ENV_PORT = "VECTORA_CLOUD_PORT";

    /** Environment variable with the service id. */
    public static final String ENV_SERVICE_ID = "VECTORA_SERVICE_ID";

    /** Environment variable with the service token. */
    public static final String ENV_TOKEN = "VECTORA_SERVICE_TOKEN";

    /** System property that overrides {@link #ENV_HOST}. */
    public static final String PROPERTY_HOST = "vectora.cloud.host";

    /** System property that overrides {@link #ENV_PORT}. */
    public static final String PROPERTY_PORT = "vectora.cloud.port";

    /** System property that overrides {@link #ENV_SERVICE_ID}. */
    public static final String PROPERTY_SERVICE_ID = "vectora.service.id";

    /**
     * System property that overrides {@link #ENV_TOKEN}. Only for manual tests: the command line
     * of a process, and with it every {@code -D} value, is visible to other users of the machine.
     */
    public static final String PROPERTY_TOKEN = "vectora.service.token";

    /** Default host if neither the variable nor the property is set. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** Default port if neither the variable nor the property is set. */
    public static final int DEFAULT_PORT = 7010;

    /**
     * Checks the settings.
     *
     * @throws IllegalArgumentException if a value is out of range, the ping interval is not
     *     shorter than the idle timeout or the minimum backoff is above the maximum
     */
    public CloudClientSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(token, "token");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be 1 to 65535, got " + port);
        }
        positive(connectTimeout, "connect timeout");
        positive(pingInterval, "ping interval");
        positive(idleTimeout, "idle timeout");
        positive(requestTimeout, "request timeout");
        positive(minBackoff, "minimum backoff");
        positive(maxBackoff, "maximum backoff");
        if (pingInterval.compareTo(idleTimeout) >= 0) {
            throw new IllegalArgumentException("ping interval must be shorter than the idle timeout");
        }
        if (minBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("minimum backoff must not exceed the maximum");
        }
    }

    /** Settings with the default timeouts: connect 5 s, ping 10 s, idle 30 s, request 10 s, backoff 1 to 30 s. */
    public static CloudClientSettings of(String host, int port, ServiceId service, AuthToken token) {
        return new CloudClientSettings(host, port, service, token, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /**
     * Reads the settings the cloud passes to the service process. For each value a system
     * property wins over the environment variable:
     *
     * <table>
     *   <caption>Sources</caption>
     *   <tr><th>Environment</th><th>System property</th><th>Default</th></tr>
     *   <tr><td>{@value #ENV_HOST}</td><td>{@value #PROPERTY_HOST}</td><td>{@value #DEFAULT_HOST}</td></tr>
     *   <tr><td>{@value #ENV_PORT}</td><td>{@value #PROPERTY_PORT}</td><td>{@value #DEFAULT_PORT}</td></tr>
     *   <tr><td>{@value #ENV_SERVICE_ID}</td><td>{@value #PROPERTY_SERVICE_ID}</td><td>required</td></tr>
     *   <tr><td>{@value #ENV_TOKEN}</td><td>{@value #PROPERTY_TOKEN}</td><td>required</td></tr>
     * </table>
     *
     * @throws IllegalStateException if a required value is missing or a value is invalid; the
     *     message names the variable but never contains the token
     */
    public static CloudClientSettings fromEnvironment() {
        return from(System::getenv, System::getProperty);
    }

    /**
     * Like {@link #fromEnvironment()}, with the two sources given, for tests.
     *
     * @param environment looks up an environment variable, {@code null} if unset
     * @param properties looks up a system property, {@code null} if unset
     */
    public static CloudClientSettings from(UnaryOperator<String> environment, UnaryOperator<String> properties) {
        String host = lookup(environment, properties, ENV_HOST, PROPERTY_HOST);
        String port = lookup(environment, properties, ENV_PORT, PROPERTY_PORT);
        String service = lookup(environment, properties, ENV_SERVICE_ID, PROPERTY_SERVICE_ID);
        String token = lookup(environment, properties, ENV_TOKEN, PROPERTY_TOKEN);
        if (service == null) {
            throw new IllegalStateException(ENV_SERVICE_ID + " is not set; is this server started by VectoraCloud?");
        }
        if (token == null) {
            throw new IllegalStateException(ENV_TOKEN + " is not set; is this server started by VectoraCloud?");
        }
        int portNumber;
        try {
            portNumber = port == null ? DEFAULT_PORT : Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(ENV_PORT + " is not a number", e);
        }
        ServiceId serviceId;
        AuthToken authToken;
        try {
            serviceId = new ServiceId(service);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ENV_SERVICE_ID + " is invalid: " + e.getMessage(), e);
        }
        try {
            authToken = new AuthToken(token);
        } catch (IllegalArgumentException e) {
            // The message of AuthToken never contains the value.
            throw new IllegalStateException(ENV_TOKEN + " is invalid: " + e.getMessage(), e);
        }
        try {
            return of(host == null ? DEFAULT_HOST : host, portNumber, serviceId, authToken);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cloud address is invalid: " + e.getMessage(), e);
        }
    }

    /** A copy with other timeouts and backoff, for tests and special setups. */
    public CloudClientSettings withTimeouts(Duration connect, Duration ping, Duration idle, Duration request,
            Duration backoffMin, Duration backoffMax) {
        return new CloudClientSettings(host, port, service, token, connect, ping, idle, request, backoffMin,
                backoffMax);
    }

    private static String lookup(UnaryOperator<String> environment, UnaryOperator<String> properties, String env,
            String property) {
        String value = properties.apply(property);
        if (value == null || value.isBlank()) {
            value = environment.apply(env);
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void positive(Duration value, String what) {
        Objects.requireNonNull(value, what);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(what + " must be positive");
        }
    }
}
