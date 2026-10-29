package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.vectora.cloud.bridge.KickClassifier.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KickClassifierTest {

    private static Verdict key(String key) {
        return KickClassifier.classify(KickReason.of(List.of(key), ""));
    }

    private static Verdict text(String text) {
        return KickClassifier.classify(KickReason.of(List.of(), text));
    }

    @Test
    void aMissingOrEmptyReasonIsALostConnection() {
        assertEquals(Verdict.SERVER_GONE, KickClassifier.classify(KickReason.missing()));
        assertEquals(Verdict.SERVER_GONE, KickClassifier.classify(KickReason.of(List.of(), "")));
        assertEquals(Verdict.SERVER_GONE, text("  §c "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplayer.disconnect.server_shutdown", "multiplayer.disconnect.server_full",
            "disconnect.timeout", "disconnect.genericReason", "disconnect.closed", "disconnect.lost",
            "disconnect.endOfStream", "disconnect.overflow"})
    void shutdownTimeoutErrorAndFullKeysAreCaughtWithTheNotice(String key) {
        assertEquals(Verdict.SERVER_GONE, key(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplayer.disconnect.banned", "multiplayer.disconnect.banned.reason",
            "multiplayer.disconnect.banned.expiration", "multiplayer.disconnect.banned_ip.reason",
            "multiplayer.disconnect.not_whitelisted", "multiplayer.disconnect.kicked", "vectora.moderation",
            "vectora.moderation.kick", "vectora.moderation.mute-kick"})
    void banWhitelistVanillaKickAndModerationKeysAreNotCaught(String key) {
        assertEquals(Verdict.NOT_CAUGHT, key(key), key);
        assertFalse(KickClassifier.rescues(KickReason.of(List.of(key), "")), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplayer.disconnect.duplicate_login",
            "multiplayer.disconnect.idling", "multiplayer.disconnect.flying", "some.plugin.key",
            "vectora.moderationx", "vectora.moderation-kick", "xvectora.moderation.kick"})
    void otherKeysAreCaughtWithTheReason(String key) {
        // Counter-check of the marker: only the key itself or with a dot behind it counts.
        assertEquals(Verdict.OTHER_REASON, key(key), key);
        assertTrue(KickClassifier.rescues(KickReason.of(List.of(key), "")), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Server closed", "server closed.", "Server is restarting", "Server restarting",
            "Timed out", "The server is full!", "Server is full", "Internal Exception: java.io.IOException: reset",
            "§cServer closed", "  Server closed  ", "Server closed!!!", "Server closed.!."})
    void shutdownTimeoutErrorAndFullTextsAreCaughtWithTheNotice(String text) {
        assertEquals(Verdict.SERVER_GONE, text(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"You are banned from this server!", "You are not white-listed on this server!",
            "Du wurdest gebannt", "Du bist gesperrt", "§cBanned: Cheating", "You are not whitelisted",
            "Du wurdest verbannt", "Wir verbannen dich", "You were tempbanned", "Tempban: 7 days",
            "Permbanned for cheating", "PermBan", "IPBan", "You are ipbanned", "You have been banished"})
    void banAndWhitelistTextsAreNotCaught(String text) {
        assertEquals(Verdict.NOT_CAUGHT, text(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Kicked by an operator.", "kicked by an operator", "§cKicked by an operator!"})
    void theVanillaKickTextOf188IsNotCaught(String text) {
        assertEquals(Verdict.NOT_CAUGHT, text(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Kicked by an operator for spamming", "Kicked by a moderator.", "Du wurdest gekickt",
            "Hacking is not allowed", "Spam",
            "Wartungsarbeiten", "Banana", "Server closed?", "!!!"})
    void unknownTextsAreCaughtWithTheReason(String text) {
        assertEquals(Verdict.OTHER_REASON, text(text), text);
    }

    @Test
    void aBanOrTheMarkerAnywhereInTheReasonWins() {
        // Each pair: the caught reason alone, then with a ban or the marker in it.
        assertEquals(Verdict.SERVER_GONE, KickClassifier.classify(
                KickReason.of(List.of("disconnect.genericReason"), "Connection reset")));
        assertEquals(Verdict.NOT_CAUGHT, KickClassifier.classify(
                KickReason.of(List.of("disconnect.genericReason"), "You were banned")));
        assertEquals(Verdict.SERVER_GONE, key("multiplayer.disconnect.server_shutdown"));
        assertEquals(Verdict.NOT_CAUGHT, KickClassifier.classify(
                KickReason.of(List.of("multiplayer.disconnect.server_shutdown", "multiplayer.disconnect.banned"), "")));
        assertEquals(Verdict.OTHER_REASON, text("Please behave"));
        assertEquals(Verdict.NOT_CAUGHT, KickClassifier.classify(
                KickReason.of(List.of("vectora.moderation.kick"), "Please behave")));
        assertEquals(Verdict.SERVER_GONE, text("Internal Exception: timeout"));
        assertEquals(Verdict.NOT_CAUGHT, text("Internal Exception: banned"));
    }

    /** A kick that may be a /kick waits for the backend's network kick; the rest is decided at once. */
    @Test
    void onlyKicksThatMayBeAKickCommandWaitForTheNetworkKick() {
        assertTrue(KickClassifier.awaitsMark(KickReason.of(List.of(), "Spam")), "/kick with a reason");
        assertTrue(KickClassifier.awaitsMark(KickReason.of(List.of("multiplayer.disconnect.kicked"), "")));
        assertTrue(KickClassifier.awaitsMark(KickReason.of(List.of(), "§cKicked by an operator.")), "1.8.8 text");
        assertTrue(KickClassifier.awaitsMark(KickReason.of(List.of("multiplayer.disconnect.idling"), "")),
                "any other key with the reason shown");
        // Counter-checks: a stop, a lost connection, a ban, the whitelist and the moderation marker.
        assertFalse(KickClassifier.awaitsMark(KickReason.of(List.of("multiplayer.disconnect.server_shutdown"), "")));
        assertFalse(KickClassifier.awaitsMark(KickReason.missing()));
        assertFalse(KickClassifier.awaitsMark(KickReason.of(List.of("multiplayer.disconnect.banned"), "")));
        assertFalse(KickClassifier.awaitsMark(KickReason.of(List.of(), "You are banned")));
        assertFalse(KickClassifier.awaitsMark(KickReason.of(List.of("multiplayer.disconnect.not_whitelisted"), "")));
        assertFalse(KickClassifier.awaitsMark(KickReason.of(List.of("vectora.moderation.kick"), "Spam")));
        assertFalse(KickClassifier.awaitsMark(
                KickReason.of(List.of("multiplayer.disconnect.kicked", "vectora.moderation.kick"), "")));
    }

    @Test
    void theMarkerIsReservedUnderVectora() {
        assertEquals("vectora.moderation", KickClassifier.MODERATION_KEY);
        assertTrue(KickClassifier.isModerationKey(KickClassifier.MODERATION_KEY + ".kick"));
        assertFalse(KickClassifier.isModerationKey("vectora.moderations.kick"));
    }
}
