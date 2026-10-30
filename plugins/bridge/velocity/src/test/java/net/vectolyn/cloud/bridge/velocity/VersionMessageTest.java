package net.vectolyn.cloud.bridge.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Only Adventure classes, so this runs on Java 21 as well. */
class VersionMessageTest {

    @Test
    void versionLineIsGrayWithTheVersionInCyan() {
        Component line = VersionMessage.of("0.2.0", null).getFirst();
        assertEquals("This network runs VectolynCloud 0.2.0", plain(line));
        TextComponent label = (TextComponent) line.children().get(0);
        TextComponent version = (TextComponent) line.children().get(1);
        assertEquals(NamedTextColor.GRAY, label.color());
        assertEquals(VersionMessage.CYAN, version.color());
        assertEquals(0x00E5FF, version.color().value());
        assertNull(version.clickEvent(), "only the address is a link");
    }

    @Test
    void sourceLineLinksTheRepository() {
        Component line = VersionMessage.of("0.2.0", Locale.ENGLISH).get(1);
        assertEquals("Open source (GPL-3.0): github.com/VectoDE/VectolynCloud", plain(line));
        TextComponent address = (TextComponent) line.children().get(1);
        assertEquals(NamedTextColor.WHITE, address.color());
        assertEquals(ClickEvent.Action.OPEN_URL, address.clickEvent().action());
        assertEquals(ClickEvent.openUrl("https://github.com/VectoDE/VectolynCloud"), address.clickEvent());
    }

    @Test
    void germanClientsReadGerman() {
        List<String> lines = VersionMessage.of("0.2.0", Locale.GERMANY).stream().map(VersionMessageTest::plain).toList();
        assertEquals(List.of("Dieses Netzwerk läuft mit VectolynCloud 0.2.0",
                "Open Source (GPL-3.0): github.com/VectoDE/VectolynCloud"), lines);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
