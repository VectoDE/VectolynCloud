package net.vectora.cloud.config.model;

/**
 * Listener of the main proxy (Velocity with player-info forwarding {@code modern}).
 *
 * @param bind        address the Java listener binds to ({@code VECTORA_PROXY_BIND})
 * @param port        Java port ({@code VECTORA_PROXY_PORT})
 * @param bedrockPort Geyser port, UDP plus TCP for signaling ({@code VECTORA_BEDROCK_PORT})
 */
public record ProxyConfig(String bind, int port, int bedrockPort) {

    /** {@code 0.0.0.0:25565}, Bedrock on 19132. */
    public static ProxyConfig defaults() {
        return new ProxyConfig("0.0.0.0", 25565, 19132);
    }
}
