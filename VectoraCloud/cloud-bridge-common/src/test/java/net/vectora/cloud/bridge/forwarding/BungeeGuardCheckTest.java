package net.vectora.cloud.bridge.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.vectora.cloud.bridge.forwarding.BungeeGuardCheck.Accepted;
import net.vectora.cloud.bridge.forwarding.BungeeGuardCheck.Reason;
import net.vectora.cloud.bridge.forwarding.BungeeGuardCheck.Rejected;
import org.junit.jupiter.api.Test;

class BungeeGuardCheckTest {

    private final BungeeGuardCheck check = BungeeGuardCheck.with(ForwardingSecret.of(Handshakes.SECRET));

    @Test
    void theRightTokenIsAcceptedAndStrippedFromTheProperties() throws MalformedHandshakeException {
        Accepted accepted = assertInstanceOf(Accepted.class, check.check(Handshakes.withToken(Handshakes.SECRET)));
        assertEquals("127.0.0.1", accepted.serverHostname());
        assertEquals("203.0.113.7", accepted.playerAddress());
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), accepted.uuid());
        assertFalse(accepted.propertiesJson().contains(Handshakes.SECRET), "token must not reach the server");
        assertFalse(accepted.propertiesJson().contains(BungeeGuardCheck.TOKEN_PROPERTY));
        assertEquals(List.of(new GameProperty("textures", "ewogICJ0aW1lc3RhbXAiIDog", "c2lnbmF0dXJl")),
                PropertiesJson.parse(accepted.propertiesJson()));
    }

    @Test
    void aWrongTokenIsRejected() {
        assertEquals(new Rejected(Reason.WRONG_TOKEN), check.check(Handshakes.withToken("s3cr3t.Legacy-Forwarding_Value+43=")));
        assertEquals(new Rejected(Reason.WRONG_TOKEN), check.check(Handshakes.withToken(Handshakes.SECRET + "x")));
        assertEquals(new Rejected(Reason.WRONG_TOKEN), check.check(Handshakes.withToken("")));
        // The secret of the main proxy (modern forwarding) is a different value and must not work.
        assertEquals(new Rejected(Reason.WRONG_TOKEN), check.check(Handshakes.withToken("main-proxy-secret-0123456789")));
    }

    @Test
    void aMissingTokenIsRejected() {
        assertEquals(new Rejected(Reason.MISSING_TOKEN), check.check(Handshakes.forwarded("[" + Handshakes.TEXTURES + "]")));
        assertEquals(new Rejected(Reason.MISSING_TOKEN), check.check(Handshakes.forwarded("[]")));
        assertEquals(new Rejected(Reason.MISSING_TOKEN),
                check.check("127.0.0.1\u0000203.0.113.7\u0000" + Handshakes.UUID_HEX), "plain BungeeCord forwarding");
    }

    @Test
    void twoTokensAreRejectedEvenIfOneIsRight() {
        String json = "[{\"name\":\"bungeeguard-token\",\"value\":\"" + Handshakes.SECRET + "\"},"
                + "{\"name\":\"bungeeguard-token\",\"value\":\"" + Handshakes.SECRET + "\"}]";
        assertEquals(new Rejected(Reason.WRONG_TOKEN), check.check(Handshakes.forwarded(json)));
    }

    @Test
    void aDirectConnectionOrBrokenHandshakeIsRejected() {
        assertEquals(new Rejected(Reason.MALFORMED), check.check("play.example.net"), "direct connection");
        assertEquals(new Rejected(Reason.MALFORMED), check.check(null));
        assertEquals(new Rejected(Reason.MALFORMED), check.check(Handshakes.withToken(Handshakes.SECRET) + "\u0000x"));
        assertEquals(new Rejected(Reason.MALFORMED),
                check.check("127.0.0.1\u0000evil.example.net\u0000" + Handshakes.UUID_HEX + "\u0000[]"), "host name as player IP");
        assertEquals(new Rejected(Reason.MALFORMED),
                check.check("127.0.0.1\u0000203.0.113.7\u0000not-a-uuid\u0000[]"));
        assertEquals(new Rejected(Reason.MALFORMED), check.check(Handshakes.forwarded("[{\"name\":1}]")));
    }

    @Test
    void withoutASecretEverythingIsRejected() {
        BungeeGuardCheck none = BungeeGuardCheck.withoutSecret();
        assertEquals(new Rejected(Reason.NO_SECRET), none.check(Handshakes.withToken(Handshakes.SECRET)));
        assertEquals(new Rejected(Reason.NO_SECRET), none.check("play.example.net"));
    }

    @Test
    void theSecretFollowsTheTemplateRuleAndIsNeverPrinted() {
        ForwardingSecret secret = ForwardingSecret.of(Handshakes.SECRET);
        assertTrue(secret.matches(Handshakes.SECRET));
        assertFalse(secret.matches(Handshakes.SECRET.substring(1)));
        assertFalse(secret.toString().contains(Handshakes.SECRET));
        IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
                () -> ForwardingSecret.of("short-secret"));
        assertFalse(tooShort.getMessage().contains("short-secret"));
        assertThrows(IllegalArgumentException.class, () -> ForwardingSecret.of("has space in the secret!"));
        assertThrows(IllegalArgumentException.class, () -> ForwardingSecret.of("quote\"inside-the-secret"));
    }

    @Test
    void ipv6PlayersAreAccepted() {
        String handshake = "backend.local\u00002001:db8::1%eth0\u0000" + Handshakes.UUID_HEX
                + "\u0000[{\"name\":\"bungeeguard-token\",\"value\":\"" + Handshakes.SECRET + "\"}]";
        Accepted accepted = assertInstanceOf(Accepted.class, check.check(handshake));
        assertEquals("2001:db8::1%eth0", accepted.playerAddress());
        assertEquals("[]", accepted.propertiesJson());
    }
}
