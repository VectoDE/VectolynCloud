package net.vectolyn.cloud.config.model;

/**
 * Listener of the main proxy (Velocity with player-info forwarding {@code modern}).
 *
 * <p>Geyser accepts both Bedrock transports: RakNet on UDP {@code bedrockPort}, NetherNet with its
 * built-in signaling on TCP {@code bedrockPort} and its WebRTC traffic on UDP
 * {@link #netherNetPort()}. RakNet and NetherNet cannot share one UDP port, so the NetherNet port
 * is always the next one.
 *
 * @param bind        address the Java listener binds to ({@code VECTOLYN_PROXY_BIND})
 * @param port        Java port ({@code VECTOLYN_PROXY_PORT})
 * @param bedrockPort Geyser port: UDP for RakNet plus TCP for NetherNet signaling
 *                    ({@code VECTOLYN_BEDROCK_PORT})
 */
public record ProxyConfig(String bind, int port, int bedrockPort) {

    /** {@code 0.0.0.0:25565}, Bedrock on 19132 (NetherNet on 19133). */
    public static ProxyConfig defaults() {
        return new ProxyConfig("0.0.0.0", 25565, 19132);
    }

    /**
     * The UDP port of Geyser's NetherNet (WebRTC) connections: {@code bedrock-port + 1}
     * ({@code VECTOLYN_BEDROCK_NETHERNET_PORT}).
     *
     * @return the port; above 65535 only for a {@code bedrock-port} the validator rejects
     */
    public int netherNetPort() {
        return bedrockPort + 1;
    }
}
