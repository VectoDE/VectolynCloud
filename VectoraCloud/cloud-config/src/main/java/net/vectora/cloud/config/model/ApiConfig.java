package net.vectora.cloud.config.model;

/**
 * REST and WebSocket API of the cloud (stage 2, PR 15).
 *
 * @param enabled whether the API listens at all
 * @param bind    address to bind to; anything except loopback needs TLS
 * @param port    port
 * @param tls     TLS settings
 */
public record ApiConfig(boolean enabled, String bind, int port, TlsConfig tls) {

    /** Off, {@code 127.0.0.1:8085}, no TLS. */
    public static ApiConfig defaults() {
        return new ApiConfig(false, "127.0.0.1", 8085, TlsConfig.disabled());
    }
}
