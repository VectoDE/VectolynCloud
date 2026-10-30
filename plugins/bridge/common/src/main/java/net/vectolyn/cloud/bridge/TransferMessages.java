package net.vectolyn.cloud.bridge;

import java.util.Locale;
import java.util.Objects;
import net.vectolyn.protocol.message.TransferPlayer;

/**
 * The lines a player reads when the cloud or the proxy moves them to another server or cannot:
 * German for a client set to German, English for every other language and when the client has not
 * told its language yet.
 */
public final class TransferMessages {

    private TransferMessages() {
    }

    /**
     * The message for a player.
     *
     * @param reason why the player is moved
     * @param locale the client's language, or {@code null} if unknown
     * @return plain text without formatting codes
     */
    public static String text(TransferPlayer.Reason reason, Locale locale) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case SCALE_DOWN -> german(locale)
                    ? "Dieser Server wird wegen geringer Auslastung heruntergefahren. Du wirst auf einen anderen Server verschoben."
                    : "This server is shutting down because only a few players are on it. You are being moved to another server.";
        };
    }

    /**
     * The message for a player whose server went away and who is sent to another one.
     *
     * @param locale the client's language, or {@code null} if unknown
     * @return plain text without formatting codes
     */
    public static String rescued(Locale locale) {
        return german(locale)
                ? "Dein Server ist nicht mehr erreichbar. Du wirst auf einen anderen Server gebracht."
                : "Your server is no longer available. You are being sent to another server.";
    }

    /**
     * The disconnect text for a player whose server went away without a reason and for whom no
     * other server is free.
     *
     * @param locale the client's language, or {@code null} if unknown
     * @return plain text without formatting codes
     */
    public static String lost(Locale locale) {
        return german(locale)
                ? "Die Verbindung zum Server ist abgebrochen, und gerade ist kein anderer Server frei."
                : "The connection to the server was lost, and no other server is free right now.";
    }

    /**
     * The disconnect text of a network kick whose backend gave no reason of its own (the vanilla
     * {@code /kick} without a reason).
     *
     * @param locale the client's language, or {@code null} if unknown
     * @return plain text without formatting codes
     */
    public static String kicked(Locale locale) {
        return german(locale)
                ? "Du wurdest vom Netzwerk geworfen."
                : "You were kicked from the network.";
    }

    /**
     * The message for a player who wants onto a server the cloud is emptying.
     *
     * @param locale the client's language, or {@code null} if unknown
     * @return plain text without formatting codes
     */
    public static String closed(Locale locale) {
        return german(locale)
                ? "Dieser Server nimmt gerade keine Spieler auf, weil er bald heruntergefahren wird."
                : "This server is not taking players right now because it is about to shut down.";
    }

    private static boolean german(Locale locale) {
        return locale != null && "de".equals(locale.getLanguage());
    }
}
