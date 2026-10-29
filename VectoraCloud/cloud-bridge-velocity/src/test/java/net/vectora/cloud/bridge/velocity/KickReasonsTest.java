package net.vectora.cloud.bridge.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.vectora.cloud.bridge.KickClassifier;
import net.vectora.cloud.bridge.KickReason;
import org.junit.jupiter.api.Test;

/** Only Adventure classes, so this runs on Java 21 as well. */
class KickReasonsTest {

    @Test
    void noComponentIsAMissingReason() {
        assertEquals(KickReason.missing(), KickReasons.of(Optional.empty()));
    }

    @Test
    void keysAndTextsAreCollectedDepthFirstWithArguments() {
        Component reason = Component.translatable("multiplayer.disconnect.banned.reason", Component.text("Cheating"))
                .append(Component.text(" / "))
                .append(Component.translatable("disconnect.timeout"));
        assertEquals(KickReason.of(List.of("multiplayer.disconnect.banned.reason", "disconnect.timeout"), "Cheating / "),
                KickReasons.of(Optional.of(reason)));
    }

    @Test
    void theFlattenedReasonDecidesTheRescue() {
        assertTrue(KickClassifier.rescues(KickReasons.of(Optional.of(
                Component.translatable("multiplayer.disconnect.server_shutdown")))));
        assertTrue(KickClassifier.rescues(KickReasons.of(Optional.of(Component.text("Server closed")))));
        // Counter-check: a ban hidden in a child or an argument is found.
        assertFalse(KickClassifier.rescues(KickReasons.of(Optional.of(Component.text("Server closed")
                .append(Component.translatable("multiplayer.disconnect.banned"))))));
        assertFalse(KickClassifier.rescues(KickReasons.of(Optional.of(
                Component.translatable("disconnect.genericReason", Component.text("You were kicked"))))));
    }
}
