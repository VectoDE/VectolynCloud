package net.vectolyn.cloud.bridge.velocity;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.vectolyn.cloud.bridge.PoweredBy;

/**
 * The chat lines of {@code /version} ({@link PoweredBy}) as Adventure components. The address is a
 * link on Java; Geyser drops click events, so Bedrock players read the same text without one. Only
 * Adventure types, so it is tested on Java 21 as well.
 */
final class VersionMessage {

    /** Cyan of the brand, {@code #00E5FF}. */
    static final TextColor CYAN = TextColor.color(0x00E5FF);

    private VersionMessage() {
    }

    /**
     * The two chat lines.
     *
     * @param version the cloud's version
     * @param locale  the client's language, or {@code null}
     * @return gray label and cyan version, then gray label and the white, clickable address
     */
    static List<Component> of(String version, Locale locale) {
        List<PoweredBy.Line> lines = PoweredBy.lines(version, locale);
        PoweredBy.Line product = lines.get(0);
        PoweredBy.Line source = lines.get(1);
        return List.of(
                Component.text().append(Component.text(product.label(), NamedTextColor.GRAY))
                        .append(Component.text(product.value(), CYAN)).build(),
                Component.text().append(Component.text(source.label(), NamedTextColor.GRAY))
                        .append(Component.text(source.value(), NamedTextColor.WHITE)
                                .clickEvent(ClickEvent.openUrl(PoweredBy.REPOSITORY_URL)))
                        .build());
    }
}
