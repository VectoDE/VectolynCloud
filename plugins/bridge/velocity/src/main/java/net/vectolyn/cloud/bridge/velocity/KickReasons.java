package net.vectolyn.cloud.bridge.velocity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.vectolyn.cloud.bridge.KickReason;

/**
 * Flattens a kick reason component into a {@link KickReason}: the translation keys and the text
 * parts, depth first, arguments included. Only Adventure types, so it is tested on Java 21 too.
 */
final class KickReasons {

    private KickReasons() {
    }

    /**
     * The reason of a kick.
     *
     * @param reason what {@code KickedFromServerEvent.getServerKickReason()} returned
     * @return the flattened reason, {@link KickReason#missing()} if there is none
     */
    static KickReason of(Optional<Component> reason) {
        if (reason.isEmpty()) {
            return KickReason.missing();
        }
        List<String> keys = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        collect(reason.get(), keys, text);
        return KickReason.of(keys, text.toString());
    }

    private static void collect(Component component, List<String> keys, StringBuilder text) {
        if (component instanceof TranslatableComponent translatable) {
            keys.add(translatable.key());
            for (TranslationArgument argument : translatable.arguments()) {
                if (argument.value() instanceof Component inner) {
                    collect(inner, keys, text);
                }
            }
        } else if (component instanceof TextComponent plain) {
            text.append(plain.content());
        }
        for (Component child : component.children()) {
            collect(child, keys, text);
        }
    }
}
