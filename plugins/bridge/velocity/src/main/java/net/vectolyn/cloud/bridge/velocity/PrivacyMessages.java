// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.bridge.velocity;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * Turns the cloud's answer to a {@code /privacy} request into chat lines. The notice and the data
 * come from the cloud as they are; only the short status lines are worded here, in English and
 * German. Plain text only: nothing from the cloud is interpreted as formatting.
 */
final class PrivacyMessages {

    private PrivacyMessages() {
    }

    /**
     * The lines to show.
     *
     * @param answer  the cloud's answer: a status line, then the content
     * @param request the request that was sent, for example {@code grant}
     * @param german  whether to word the status lines in German
     * @return the lines
     */
    static List<Component> lines(String answer, String request, boolean german) {
        String[] parts = answer.split("\n", -1);
        List<Component> result = new ArrayList<>();
        String head = parts[0].strip();
        if (head.startsWith("error")) {
            result.add(Component.text(error(head, german)));
            return result;
        }
        if (parts.length > 1) {
            if (parts.length == 2 && (parts[1].equals("consented") || parts[1].equals("not-consented"))) {
                boolean on = parts[1].equals("consented");
                result.add(Component.text(german
                        ? (on ? "Du hast zugestimmt." : "Du hast nicht zugestimmt. Es wird nichts über dich gespeichert.")
                        : (on ? "You have agreed." : "You have not agreed. Nothing about you is stored.")));
                return result;
            }
            for (int i = 1; i < parts.length; i++) {
                result.add(Component.text(parts[i]));
            }
            return result;
        }
        switch (request) {
            case "grant" -> result.add(Component.text(german
                    ? "Danke. Deine Aktivitätsdaten werden ab jetzt gespeichert. /privacy off löscht alles."
                    : "Thank you. Your activity data is stored from now on. /privacy off deletes everything."));
            case "revoke" -> result.add(Component.text(german
                    ? "Alles über dich wurde gelöscht." : "Everything about you has been deleted."));
            default -> result.add(Component.text("OK"));
        }
        return result;
    }

    private static String error(String head, boolean german) {
        return switch (head) {
            case "error disabled" -> german ? "Diese Funktion ist auf diesem Netzwerk nicht eingeschaltet."
                    : "This feature is not enabled on this network.";
            case "error not-online" -> german ? "Das geht nur, solange du mit dem Netzwerk verbunden bist."
                    : "This only works while you are connected to the network.";
            case "error unavailable" -> german ? "Gerade nicht möglich. Versuche es später noch einmal."
                    : "Not possible right now. Please try again later.";
            default -> german ? "Ungültige Anfrage." : "Invalid request.";
        };
    }
}
