package net.vectora.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// PortalBridge extends JavaPlugin, so loading it needs the Java 25 runtime of paper-api 26.3.
@Tag("requires-java25")
class PortalBridgeTest {

    @Test
    void connectPayloadIsReadableAsBungeeCordConnectMessage() throws IOException {
        byte[] payload = PortalBridge.connectPayload("end");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals("Connect", in.readUTF());
            assertEquals("end", in.readUTF());
            assertEquals(0, in.available(), "payload must not contain trailing bytes");
        }
    }

    @Test
    void connectPayloadKeepsNonAsciiServerNames() throws IOException {
        byte[] payload = PortalBridge.connectPayload("ende-ö");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.readUTF();
            assertEquals("ende-ö", in.readUTF());
        }
    }
}
