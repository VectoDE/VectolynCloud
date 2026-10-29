package net.vectora.cloud.bridge;

import java.util.Locale;
import java.util.Objects;
import net.vectora.protocol.message.TransferPlayer;

/**
 * The line a player reads when the cloud moves them to another server: German for a client set
 * to German, English for every other language and when the client has not told its language yet.
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
        boolean german = locale != null && "de".equals(locale.getLanguage());
        return switch (reason) {
            case SCALE_DOWN -> german
                    ? "Dieser Server wird wegen geringer Auslastung heruntergefahren. Du wirst auf einen anderen Server verschoben."
                    : "This server is shutting down because only a few players are on it. You are being moved to another server.";
        };
    }
}
