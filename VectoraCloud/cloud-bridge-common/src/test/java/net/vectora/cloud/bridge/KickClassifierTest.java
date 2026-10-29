package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KickClassifierTest {

    private static boolean key(String key) {
        return KickClassifier.rescues(KickReason.of(List.of(key), ""));
    }

    private static boolean text(String text) {
        return KickClassifier.rescues(KickReason.of(List.of(), text));
    }

    @Test
    void aMissingReasonIsALostConnection() {
        assertTrue(KickClassifier.rescues(KickReason.missing()));
        // Counter-check: an empty reason the server did send is not a lost connection.
        assertFalse(KickClassifier.rescues(KickReason.of(List.of(), "")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplayer.disconnect.server_shutdown", "multiplayer.disconnect.server_full",
            "disconnect.timeout", "disconnect.genericReason", "disconnect.closed", "disconnect.lost",
            "disconnect.endOfStream", "disconnect.overflow"})
    void shutdownTimeoutErrorAndFullKeysAreRescued(String key) {
        assertTrue(key(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplayer.disconnect.banned", "multiplayer.disconnect.banned.reason",
            "multiplayer.disconnect.banned.expiration", "multiplayer.disconnect.banned_ip.reason",
            "multiplayer.disconnect.not_whitelisted", "multiplayer.disconnect.kicked",
            "multiplayer.disconnect.duplicate_login", "multiplayer.disconnect.idling", "multiplayer.disconnect.flying",
            "some.plugin.key"})
    void banWhitelistKickAndUnknownKeysAreNotRescued(String key) {
        assertFalse(key(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Server closed", "server closed.", "Server is restarting", "Server restarting",
            "Timed out", "The server is full!", "Server is full", "Internal Exception: java.io.IOException: reset",
            "§cServer closed", "  Server closed  "})
    void shutdownTimeoutErrorAndFullTextsAreRescued(String text) {
        assertTrue(text(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"You are banned from this server!", "You are not white-listed on this server!",
            "Kicked by an operator.", "Du wurdest gebannt", "Du wurdest gekickt", "Hacking is not allowed",
            "Spam", "Wartungsarbeiten"})
    void banKickAndUnknownTextsAreNotRescued(String text) {
        assertFalse(text(text), text);
    }

    @Test
    void aBanOrKickAnywhereInTheReasonWins() {
        // Each pair: the rescuable reason alone, then with a ban or kick part.
        assertTrue(KickClassifier.rescues(KickReason.of(List.of("disconnect.genericReason"), "Connection reset")));
        assertFalse(KickClassifier.rescues(KickReason.of(List.of("disconnect.genericReason"), "You were kicked")));
        assertTrue(KickClassifier.rescues(KickReason.of(List.of("multiplayer.disconnect.server_shutdown"), "")));
        assertFalse(KickClassifier.rescues(
                KickReason.of(List.of("multiplayer.disconnect.server_shutdown", "multiplayer.disconnect.banned"), "")));
        assertTrue(text("Internal Exception: timeout"));
        assertFalse(text("Internal Exception: banned"));
    }
}
