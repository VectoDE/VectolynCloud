package net.vectolyn.cloud.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.vectolyn.cloud.bridge.forwarding.AdmissionTickets;
import net.vectolyn.cloud.bridge.forwarding.BungeeGuardCheck;
import net.vectolyn.cloud.bridge.forwarding.ForwardingSecret;
import org.junit.jupiter.api.Test;

/**
 * Runs the guard against the real {@code PlayerHandshakeEvent} of the PandaSpigot API (Java 8
 * class files, so this also runs on Java 21).
 */
class HandshakeGuardTest {

    private static final String SECRET = "legacy-forwarding-secret.0123456789";
    private static final String UUID_HEX = "069a79f444e94726a5befca90e38aaf5";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String TEXTURES = "{\"name\":\"textures\",\"value\":\"dGV4dHVyZQ==\",\"signature\":\"c2ln\"}";

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger log = captureLogger();
    private final AdmissionTickets tickets = new AdmissionTickets();
    private final HandshakeGuard guard =
            new HandshakeGuard(BungeeGuardCheck.with(ForwardingSecret.of(SECRET)), tickets, log);

    @Test
    void theRightTokenIsAcceptedWithoutPassingTheTokenOn() {
        PlayerHandshakeEvent event = event(withToken(SECRET));
        guard.onHandshake(event);
        guard.enforce(event);

        assertFalse(event.isCancelled(), "PandaSpigot only takes the event's values if it is not cancelled");
        assertFalse(event.isFailed());
        assertEquals("127.0.0.1", event.getServerHostname());
        assertEquals("203.0.113.7", event.getSocketAddressHostname());
        assertEquals(PLAYER, event.getUniqueId());
        assertEquals("[" + TEXTURES + "]", event.getPropertiesJson());
        assertFalse(event.getPropertiesJson().contains(SECRET));
        assertTrue(tickets.consume(PLAYER), "an accepted handshake leaves a login ticket");
    }

    @Test
    void aWrongTokenFailsTheHandshake() {
        PlayerHandshakeEvent event = event(withToken("legacy-forwarding-secret.0123456780"));
        guard.onHandshake(event);
        assertRefused(event);
        assertEquals(1, records.size());
        assertTrue(records.get(0).getParameters()[1].toString().equals("WRONG_TOKEN"));
        assertFalse(String.valueOf(records.get(0).getParameters()[0]).contains(SECRET));
    }

    @Test
    void aMissingTokenFailsTheHandshake() {
        PlayerHandshakeEvent event = event(forwarded("[" + TEXTURES + "]"));
        guard.onHandshake(event);
        assertRefused(event);
        assertEquals("MISSING_TOKEN", records.get(0).getParameters()[1].toString());
    }

    @Test
    void aDirectConnectionFailsTheHandshake() {
        PlayerHandshakeEvent event = event("play.example.net");
        guard.onHandshake(event);
        assertRefused(event);
    }

    @Test
    void anotherPluginCannotLetARefusedHandshakeThrough() {
        PlayerHandshakeEvent event = event(withToken("wrong-secret-wrong-secret"));
        guard.onHandshake(event);
        // A careless plugin in between: clears the failure and cancels (= unchecked default parsing).
        event.setFailed(false);
        event.setCancelled(true);
        guard.enforce(event);
        assertRefused(event);
    }

    @Test
    void anotherPluginCannotMakeTheServerParseTheTokenItself() {
        PlayerHandshakeEvent event = event(withToken(SECRET));
        guard.onHandshake(event);
        event.setCancelled(true);
        event.setPropertiesJson(null);
        guard.enforce(event);
        assertFalse(event.isCancelled());
        assertEquals("[" + TEXTURES + "]", event.getPropertiesJson());
    }

    @Test
    void withoutASecretEveryHandshakeIsRefused() {
        List<LogRecord> setup = new ArrayList<>();
        Logger setupLog = captureLogger(setup);
        HandshakeGuard none = HandshakeGuard.fromEnvironment(Map.<String, String>of()::get, setupLog);
        PlayerHandshakeEvent event = event(withToken(SECRET));
        none.onHandshake(event);
        assertRefused(event);
        assertTrue(setup.get(0).getMessage().contains(ForwardingSecret.ENV));

        HandshakeGuard invalid = HandshakeGuard.fromEnvironment(Map.of(ForwardingSecret.ENV, "short")::get, setupLog);
        PlayerHandshakeEvent second = event(withToken("short"));
        invalid.onHandshake(second);
        assertRefused(second);
        for (LogRecord record : setup) {
            assertFalse(record.getMessage().contains("short\""), "the secret value is never logged");
        }

        // Counter-check: the same environment with a valid secret accepts.
        HandshakeGuard valid = HandshakeGuard.fromEnvironment(Map.of(ForwardingSecret.ENV, SECRET)::get, setupLog);
        PlayerHandshakeEvent third = event(withToken(SECRET));
        valid.onHandshake(third);
        assertFalse(third.isFailed());
    }

    @Test
    void refusalsAreLoggedWithALimit() {
        for (int i = 0; i < 50; i++) {
            guard.onHandshake(event("play.example.net"));
        }
        assertEquals(10, records.size());
    }

    private static void assertRefused(PlayerHandshakeEvent event) {
        assertFalse(event.isCancelled(), "a failure only counts if the event is not cancelled");
        assertTrue(event.isFailed());
        assertEquals(HandshakeGuard.FAIL_MESSAGE, event.getFailMessage());
        assertNull(event.getUniqueId());
    }

    private static PlayerHandshakeEvent event(String handshake) {
        // PandaSpigot creates it with cancelled = !bungeecord; the backends run with bungeecord: true.
        return new PlayerHandshakeEvent(handshake, "198.51.100.20", false);
    }

    private static String forwarded(String properties) {
        return "127.0.0.1\u0000203.0.113.7\u0000" + UUID_HEX + "\u0000" + properties;
    }

    private static String withToken(String token) {
        return forwarded("[" + TEXTURES + ",{\"name\":\"bungeeguard-token\",\"value\":\"" + token + "\"}]");
    }

    private Logger captureLogger() {
        return captureLogger(records);
    }

    private static Logger captureLogger(List<LogRecord> into) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                into.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @Test
    void theResultTypesAreTheCheckOnes() {
        assertInstanceOf(BungeeGuardCheck.Accepted.class, guard.apply(event(withToken(SECRET))));
    }
}
