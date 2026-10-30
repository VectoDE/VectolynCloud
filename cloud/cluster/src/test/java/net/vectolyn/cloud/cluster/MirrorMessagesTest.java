package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.vectolyn.cloud.cluster.MirrorMessages.Changed;
import net.vectolyn.cloud.cluster.MirrorMessages.ChunkRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.Manifest;
import net.vectolyn.cloud.cluster.MirrorMessages.ManifestRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateList;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.ProtocolException;
import net.vectolyn.protocol.message.MessageLimits;
import org.junit.jupiter.api.Test;

/** Round trips and limits of the mirror's payloads. */
class MirrorMessagesTest {

    private static final byte[] SHA = MirrorMessages.sha256(new byte[] {1});

    @Test
    void payloadsRoundTrip() {
        Changed changed = new Changed(42);
        assertEquals(changed, Changed.read(new PacketReader(changed.write())));
        ManifestRequest request = new ManifestRequest("Lobby", SHA, 3);
        assertEquals(request, ManifestRequest.read(new PacketReader(request.write())));
        Manifest manifest = new Manifest(7, SHA, 2, 0, List.of(new FileEntry("a.txt", 5, SHA), new FileEntry("b/c", 0, SHA)));
        assertEquals(manifest, Manifest.read(new PacketReader(manifest.write())));
        ChunkRequest chunk = new ChunkRequest("", SHA, "groups/lobby.yml", 49152, MirrorMessages.CHUNK);
        assertEquals(chunk, ChunkRequest.read(new PacketReader(chunk.write())));
        TemplateList list = new TemplateList(List.of(new TemplateEntry("Lobby", SHA, 3, 1000)));
        assertEquals(list, TemplateList.read(new PacketReader(list.write())));
    }

    @Test
    void fullPagesFitIntoOneAnswer() {
        String longPath = "p".repeat(100) + "/" + "q".repeat(120) + "/" + "r".repeat(18);
        List<FileEntry> entries = new ArrayList<>();
        for (int i = 0; i < MirrorMessages.TEMPLATE_PAGE; i++) {
            entries.add(new FileEntry(longPath.substring(0, longPath.length() - 3) + String.format("%03d", i),
                    MirrorPaths.MAX_TEMPLATE_FILE_BYTES, SHA));
        }
        int size = new Manifest(0, SHA, Integer.MAX_VALUE, MirrorMessages.MAX_PAGES - 1, entries).write().length;
        assertTrue(size <= MessageLimits.MAX_PAYLOAD_SIZE, size + " bytes");
        List<TemplateEntry> templates = new ArrayList<>();
        for (int i = 0; i < MirrorPaths.MAX_TEMPLATES; i++) {
            templates.add(new TemplateEntry(("T" + i + "x".repeat(64)).substring(0, 64), SHA, Integer.MAX_VALUE,
                    Long.MAX_VALUE));
        }
        size = new TemplateList(templates).write().length;
        assertTrue(size <= MessageLimits.MAX_PAYLOAD_SIZE, size + " bytes");
    }

    @Test
    void invalidValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new FileEntry("../x", 1, SHA));
        assertThrows(IllegalArgumentException.class, () -> new FileEntry("x", 1, new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new ChunkRequest("", SHA, "x", 0, MirrorMessages.CHUNK + 1));
        assertThrows(IllegalArgumentException.class, () -> new ManifestRequest("", new byte[0], 1));
        assertThrows(ProtocolException.class, () -> Changed.read(new PacketReader(new byte[] {1, 2})));
    }
}
