// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.util.Objects;

/**
 * The text a player reads before agreeing to the tracking: who is responsible, what for, what is
 * stored, for how long, and how to withdraw. One source for every place that shows it (in game,
 * panel). A change to the wording or to what is stored raises {@link #VERSION}; a consent always
 * records the version it was given for.
 *
 * <p>This is a draft. It has not been reviewed by a lawyer and has to be before the tracking is
 * used on a public network.
 */
public final class TrackingNotice {

    /** Version of the notice text. */
    public static final int VERSION = 1;

    private TrackingNotice() {
    }

    /**
     * The notice.
     *
     * @param german        German if true, English otherwise
     * @param controller    who is responsible, from {@code tracking.controller}
     * @param retentionDays days after the last visit until the data is deleted
     * @return the text, lines separated by {@code \n}
     */
    public static String text(boolean german, String controller, int retentionDays) {
        Objects.requireNonNull(controller, "controller");
        if (german) {
            return "Aktivitätsdaten (Version " + VERSION + ")\n"
                    + "Verantwortlich: " + controller + "\n"
                    + "Zweck: Auswertung, wie das Netzwerk und seine Spielmodi genutzt werden, damit wir sie verbessern können.\n"
                    + "Gespeichert wird: dein Spielername und deine UUID, Zeitpunkt des ersten und letzten Besuchs, "
                    + "Anzahl der Anmeldungen und Spielzeit, insgesamt und je Spielmodus.\n"
                    + "Speicherdauer: " + retentionDays + " Tage nach deinem letzten Besuch, dann wird alles gelöscht.\n"
                    + "Freiwillig: Ohne deine Zustimmung wird nichts über dich gespeichert. Du kannst jederzeit "
                    + "widerrufen; dann wird alles sofort gelöscht.\n"
                    + "Befehle: /privacy on (zustimmen), /privacy off (widerrufen und löschen), /privacy data (Auskunft), "
                    + "/privacy status.";
        }
        return "Activity data (version " + VERSION + ")\n"
                + "Responsible: " + controller + "\n"
                + "Purpose: to analyse how the network and its game modes are used, so that we can improve them.\n"
                + "What is stored: your player name and UUID, the time of your first and last visit, the number of "
                + "logins and your playtime, in total and per game mode.\n"
                + "Retention: " + retentionDays + " days after your last visit, then everything is deleted.\n"
                + "Voluntary: nothing about you is stored without your consent. You can withdraw at any time; "
                + "everything is then deleted at once.\n"
                + "Commands: /privacy on (agree), /privacy off (withdraw and delete), /privacy data (access), "
                + "/privacy status.";
    }
}
