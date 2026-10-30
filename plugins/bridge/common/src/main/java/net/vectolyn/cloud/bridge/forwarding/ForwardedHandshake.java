package net.vectolyn.cloud.bridge.forwarding;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The parts of a BungeeCord-style forwarded handshake. The server address field of the vanilla
 * handshake then reads {@code host \0 player-ip \0 uuid-without-dashes \0 properties-json}.
 *
 * @param serverHostname the host the proxy connected to
 * @param playerAddress IP literal of the player as the proxy saw it
 * @param uuid the player's UUID
 * @param properties profile properties, including the {@code bungeeguard-token} as sent
 */
public record ForwardedHandshake(String serverHostname, String playerAddress, UUID uuid,
        List<GameProperty> properties) {

    /** Longest handshake read: the field is a Minecraft string of at most 32767 characters. */
    public static final int MAX_LENGTH = Short.MAX_VALUE;

    /** Longest host part accepted (the vanilla handshake allows 255 characters). */
    public static final int MAX_HOST_LENGTH = 255;

    /** Longest IP literal accepted (IPv6 with an IPv4 tail and a zone). */
    public static final int MAX_ADDRESS_LENGTH = 64;

    /** Creates the record. */
    public ForwardedHandshake {
        Objects.requireNonNull(serverHostname, "serverHostname");
        Objects.requireNonNull(playerAddress, "playerAddress");
        Objects.requireNonNull(uuid, "uuid");
        properties = List.copyOf(properties);
    }

    /**
     * Splits and checks a forwarded handshake. Three parts (no properties) are accepted and give
     * an empty property list; the token check then fails with a missing token.
     *
     * @throws MalformedHandshakeException if the text is not a forwarded handshake
     */
    public static ForwardedHandshake parse(String handshake) throws MalformedHandshakeException {
        Objects.requireNonNull(handshake, "handshake");
        if (handshake.length() > MAX_LENGTH) {
            throw new MalformedHandshakeException("handshake longer than " + MAX_LENGTH + " characters");
        }
        String[] parts = handshake.split("\0", 5);
        if (parts.length != 3 && parts.length != 4) {
            throw new MalformedHandshakeException("not a forwarded handshake (" + parts.length + " parts)");
        }
        String host = checkHost(parts[0]);
        String address = checkAddress(parts[1]);
        UUID uuid = parseUuid(parts[2]);
        List<GameProperty> properties = parts.length == 4 ? PropertiesJson.parse(parts[3]) : List.of();
        return new ForwardedHandshake(host, address, uuid, properties);
    }

    private static String checkHost(String host) throws MalformedHandshakeException {
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            throw new MalformedHandshakeException("server host is empty or too long");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new MalformedHandshakeException("server host contains a control character");
            }
        }
        return host;
    }

    /**
     * Only IP literals: the server turns the value into an {@code InetSocketAddress}, and a host
     * name there would make it resolve DNS during the login.
     */
    private static String checkAddress(String address) throws MalformedHandshakeException {
        if (address.isEmpty() || address.length() > MAX_ADDRESS_LENGTH) {
            throw new MalformedHandshakeException("player address is empty or too long");
        }
        boolean colon = address.indexOf(':') >= 0;
        if (colon) {
            int zone = address.indexOf('%');
            String body = zone >= 0 ? address.substring(0, zone) : address;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0 && c < 0x80)) {
                    throw new MalformedHandshakeException("player address is not an IP literal");
                }
            }
            if (zone >= 0) {
                for (int i = zone + 1; i < address.length(); i++) {
                    char c = address.charAt(i);
                    if (!(c < 0x80 && (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.'))) {
                        throw new MalformedHandshakeException("player address has an invalid zone");
                    }
                }
            }
            return address;
        }
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            throw new MalformedHandshakeException("player address is not an IP literal");
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                throw new MalformedHandshakeException("player address is not an IP literal");
            }
            int value = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    throw new MalformedHandshakeException("player address is not an IP literal");
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                throw new MalformedHandshakeException("player address is not an IP literal");
            }
        }
        return address;
    }

    private static UUID parseUuid(String text) throws MalformedHandshakeException {
        if (text.length() != 32) {
            throw new MalformedHandshakeException("UUID must be 32 hex digits");
        }
        long most = 0;
        long least = 0;
        for (int i = 0; i < 32; i++) {
            char c = text.charAt(i);
            int digit = c < 0x80 ? Character.digit(c, 16) : -1;
            if (digit < 0) {
                throw new MalformedHandshakeException("UUID must be 32 hex digits");
            }
            if (i < 16) {
                most = most << 4 | digit;
            } else {
                least = least << 4 | digit;
            }
        }
        return new UUID(most, least);
    }

    /** The properties are summarised: they contain the token. */
    @Override
    public String toString() {
        return "ForwardedHandshake[serverHostname=" + serverHostname + ", playerAddress=" + playerAddress + ", uuid="
                + uuid + ", properties=" + properties.size() + "]";
    }
}
