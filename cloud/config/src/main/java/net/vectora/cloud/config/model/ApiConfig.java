package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * REST and WebSocket API of the cloud (module {@code cloud-api}, see {@code docs/cloud/api.md}).
 *
 * @param enabled     whether the API listens at all
 * @param bind        address to bind to; anything except loopback needs TLS
 * @param port        port
 * @param tls         TLS settings
 * @param corsOrigins origins a browser may call the API from (the dashboard, stage 8), for example
 *                    {@code https://dashboard.example.org}; empty means CORS is off. Never null;
 *                    not written to the file while empty.
 */
@JsonPropertyOrder({"enabled", "bind", "port", "tls", "cors-origins"})
public record ApiConfig(boolean enabled, String bind, int port, TlsConfig tls,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> corsOrigins) {

    /** Largest number of entries in {@code cors-origins}. */
    public static final int MAX_CORS_ORIGINS = 16;

    /** Copies the origin list; a missing list becomes empty. */
    public ApiConfig {
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
    }

    /** Settings without CORS. */
    public ApiConfig(boolean enabled, String bind, int port, TlsConfig tls) {
        this(enabled, bind, port, tls, List.of());
    }

    /** Off, {@code 127.0.0.1:8085}, no TLS, no CORS. */
    public static ApiConfig defaults() {
        return new ApiConfig(false, "127.0.0.1", 8085, TlsConfig.disabled());
    }

    /** A copy with the switch set. */
    public ApiConfig withEnabled(boolean value) {
        return new ApiConfig(value, bind, port, tls, corsOrigins);
    }

    /** A copy with another bind address. */
    public ApiConfig withBind(String value) {
        return new ApiConfig(enabled, value, port, tls, corsOrigins);
    }

    /** A copy with another port. */
    public ApiConfig withPort(int value) {
        return new ApiConfig(enabled, bind, value, tls, corsOrigins);
    }

    /** A copy with other TLS settings. */
    public ApiConfig withTls(TlsConfig value) {
        return new ApiConfig(enabled, bind, port, value, corsOrigins);
    }

    /** A copy with other CORS origins. */
    public ApiConfig withCorsOrigins(List<String> value) {
        return new ApiConfig(enabled, bind, port, tls, value);
    }
}
