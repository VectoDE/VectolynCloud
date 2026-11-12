// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.bridge.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class PrivacyMessagesTest {

    private static List<String> text(String answer, String request, boolean german) {
        return PrivacyMessages.lines(answer, request, german).stream()
                .map(c -> ((TextComponent) c).content()).toList();
    }

    @Test
    void noticeLinesAreShownAsTheyCame() {
        assertEquals(List.of("Activity data (version 1)", "Purpose: x"),
                text("ok\nActivity data (version 1)\nPurpose: x", "notice en", false));
    }

    @Test
    void statusAndResultsAreWordedInBothLanguages() {
        assertEquals(List.of("You have agreed."), text("ok\nconsented", "status", false));
        assertEquals(List.of("Du hast nicht zugestimmt. Es wird nichts über dich gespeichert."),
                text("ok\nnot-consented", "status", true));
        assertTrue(text("ok", "grant", false).get(0).startsWith("Thank you"));
        assertEquals(List.of("Alles über dich wurde gelöscht."), text("ok", "revoke", true));
    }

    @Test
    void errorsAreWordedAndNothingFromTheCloudIsInterpreted() {
        assertEquals(List.of("This feature is not enabled on this network."), text("error disabled", "grant", false));
        assertEquals(List.of("Ungültige Anfrage."), text("error whatever <red>", "grant", true));
        Component line = PrivacyMessages.lines("ok\n<red>&cplain", "data", false).get(0);
        assertEquals("<red>&cplain", ((TextComponent) line).content());
    }
}
