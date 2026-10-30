package net.vectolyn.cloud.bridge.forwarding;

/** Builds forwarded handshakes the way Velocity writes them in {@code bungeeguard} mode. */
final class Handshakes {

    static final String SECRET = "s3cr3t.Legacy-Forwarding_Value+42=";
    static final String UUID_HEX = "069a79f444e94726a5befca90e38aaf5";
    static final String TEXTURES = "{\"name\":\"textures\",\"value\":\"ewogICJ0aW1lc3RhbXAiIDog\",\"signature\":\"c2lnbmF0dXJl\"}";

    private Handshakes() {
    }

    static String forwarded(String propertiesJson) {
        return "127.0.0.1\u0000203.0.113.7\u0000" + UUID_HEX + "\u0000" + propertiesJson;
    }

    static String withToken(String token) {
        return forwarded("[" + TEXTURES + ",{\"name\":\"bungeeguard-token\",\"value\":\"" + token + "\"}]");
    }
}
