package net.vectora.cloud.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.ApiConfig;

/**
 * Where and how the API server listens.
 *
 * @param bind        address to bind to
 * @param port        port, 0 for any free port (tests)
 * @param tls         key material for HTTPS, or empty for plain HTTP (loopback only)
 * @param corsOrigins origins a browser may call from; empty switches CORS off
 * @param limits      limits
 */
public record ApiSettings(String bind, int port, Optional<ApiTls.Material> tls, List<String> corsOrigins,
                          ApiLimits limits) {

    /** Checks and copies the values. */
    public ApiSettings {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(limits, "limits");
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
    }

    /**
     * Settings from {@code api} in {@code cloud.yml}.
     *
     * @param api the section
     * @param tls the key material ({@link ApiTls#prepare}) when {@code api.tls.enabled}, otherwise empty
     * @return the settings with {@link ApiLimits#defaults()}
     */
    public static ApiSettings of(ApiConfig api, Optional<ApiTls.Material> tls) {
        return new ApiSettings(api.bind(), api.port(), tls, api.corsOrigins(), ApiLimits.defaults());
    }

    /**
     * A copy with other limits.
     *
     * @param value the limits
     * @return the copy
     */
    public ApiSettings withLimits(ApiLimits value) {
        return new ApiSettings(bind, port, tls, corsOrigins, value);
    }
}
