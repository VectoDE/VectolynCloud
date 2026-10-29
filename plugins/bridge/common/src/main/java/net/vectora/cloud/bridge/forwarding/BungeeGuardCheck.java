package net.vectora.cloud.bridge.forwarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides whether a forwarded handshake comes from the legacy proxy: it must carry exactly one
 * {@code bungeeguard-token} property, and that property must equal the forwarding secret. The
 * comparison runs in constant time ({@link ForwardingSecret#matches}). On success the token is
 * removed from the properties, so neither plugins nor other players ever see it.
 *
 * <p>Without a secret ({@link #withoutSecret()}) every handshake is refused: a backend whose
 * secret is missing must not fall back to accepting anyone.
 */
public final class BungeeGuardCheck {

    /** Name of the property Velocity adds in {@code bungeeguard} mode. */
    public static final String TOKEN_PROPERTY = "bungeeguard-token";

    private final ForwardingSecret secret;

    private BungeeGuardCheck(ForwardingSecret secret) {
        this.secret = secret;
    }

    /** A check against {@code secret}. */
    public static BungeeGuardCheck with(ForwardingSecret secret) {
        return new BungeeGuardCheck(Objects.requireNonNull(secret, "secret"));
    }

    /** A check that refuses every handshake with {@link Reason#NO_SECRET}. */
    public static BungeeGuardCheck withoutSecret() {
        return new BungeeGuardCheck(null);
    }

    /** Checks the handshake text exactly as the client sent it (the original server address field). */
    public Result check(String originalHandshake) {
        if (secret == null) {
            return new Rejected(Reason.NO_SECRET);
        }
        if (originalHandshake == null) {
            return new Rejected(Reason.MALFORMED);
        }
        ForwardedHandshake handshake;
        try {
            handshake = ForwardedHandshake.parse(originalHandshake);
        } catch (MalformedHandshakeException e) {
            return new Rejected(Reason.MALFORMED);
        }
        String token = null;
        int tokens = 0;
        List<GameProperty> rest = new ArrayList<>(handshake.properties().size());
        for (GameProperty property : handshake.properties()) {
            if (TOKEN_PROPERTY.equals(property.name())) {
                tokens++;
                token = property.value();
            } else {
                rest.add(property);
            }
        }
        if (tokens == 0) {
            return new Rejected(Reason.MISSING_TOKEN);
        }
        // Compare even if there are several, so the answer takes the same time either way.
        boolean match = secret.matches(token);
        if (tokens > 1 || !match) {
            return new Rejected(Reason.WRONG_TOKEN);
        }
        return new Accepted(handshake.serverHostname(), handshake.playerAddress(), handshake.uuid(),
                PropertiesJson.write(rest));
    }

    /** Outcome of {@link #check}. */
    public sealed interface Result {
    }

    /**
     * The handshake comes from the legacy proxy. The values replace what the server would take
     * from the handshake itself.
     *
     * @param serverHostname host part of the handshake
     * @param playerAddress IP literal of the player
     * @param uuid the player's UUID
     * @param propertiesJson the properties without the token, as a JSON array
     */
    public record Accepted(String serverHostname, String playerAddress, UUID uuid, String propertiesJson)
            implements Result {
    }

    /**
     * The handshake is refused.
     *
     * @param reason why; for the log, never for the player
     */
    public record Rejected(Reason reason) implements Result {
    }

    /** Why a handshake was refused. */
    public enum Reason {
        /** No forwarding secret is configured on this backend. */
        NO_SECRET,
        /** Not a forwarded handshake, or one with broken parts (for example a direct connection). */
        MALFORMED,
        /** Forwarded, but without {@code bungeeguard-token}. */
        MISSING_TOKEN,
        /** A token that is not the secret, or more than one token. */
        WRONG_TOKEN
    }
}
