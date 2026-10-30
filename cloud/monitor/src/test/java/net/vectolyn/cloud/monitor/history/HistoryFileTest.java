package net.vectolyn.cloud.monitor.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.monitor.Fakes;
import net.vectolyn.cloud.monitor.metric.NodeSample;
import net.vectolyn.cloud.monitor.metric.ServiceSample;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryFileTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");

    static HistoryFile.Content sample() {
        Map<ServiceId, List<ServiceSample>> services = new LinkedHashMap<>();
        services.put(new ServiceId("lobby-1"), List.of(
                new ServiceSample(T0, 3, 100, 19.5, 12.25, 400L << 20, 1L << 30, 4.5, 900L << 20),
                ServiceSample.empty(T0.plusSeconds(10))));
        services.put(new ServiceId("proxy-1"), List.of(
                new ServiceSample(T0, 3, 500, Double.NaN, Double.NaN, 100L << 20, -1, Double.NaN, -1)));
        return new HistoryFile.Content(List.of(new NodeSample(T0, 12.5, 4L << 30, 16L << 30, 100L << 30, 500L << 30),
                NodeSample.empty(T0.plusSeconds(10))), services);
    }

    @Test
    void roundTripKeepsEveryValueIncludingUnknowns() throws Exception {
        HistoryFile.Content content = sample();
        byte[] bytes = HistoryFile.encode(content);
        assertEquals(content, HistoryFile.decode(bytes));
        assertTrue(bytes.length < 400, "compact: " + bytes.length + " bytes");
    }

    @Test
    void damagedFilesAreRejected() {
        byte[] good = HistoryFile.encode(sample());
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(new byte[0]));
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(Arrays.copyOf(good, good.length - 1)));
        byte[] longer = Arrays.copyOf(good, good.length + 1);
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(longer), "trailing bytes");
        byte[] magic = good.clone();
        magic[3] = 2;
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(magic), "other version");
        byte[] count = good.clone();
        ByteBuffer.wrap(count).putInt(4, -1);
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(count), "negative count");
        byte[] huge = good.clone();
        ByteBuffer.wrap(huge).putInt(4, Integer.MAX_VALUE);
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(huge), "no allocation from a huge count");
        byte[] time = good.clone();
        ByteBuffer.wrap(time).putLong(8, 0);
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(time), "time before 2000");
        byte[] cpu = good.clone();
        ByteBuffer.wrap(cpu).putFloat(16, Float.POSITIVE_INFINITY);
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(cpu), "infinite value");
        byte[] bytes = good.clone();
        ByteBuffer.wrap(bytes).putLong(20, -2);
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(bytes), "-2 bytes");
    }

    @Test
    void invalidServiceIdsAreRejected() {
        HistoryFile.Content one = new HistoryFile.Content(List.of(),
                Map.of(new ServiceId("lobby-1"), List.of(ServiceSample.empty(T0))));
        byte[] bytes = HistoryFile.encode(one);
        // magic 4, node count 4, service count 4, id length 1, then the id.
        bytes[13] = 'L';
        assertThrows(HistoryFile.FormatException.class, () -> HistoryFile.decode(bytes));
    }

    @Test
    void storeTrimsByRetentionAndCapacity(@TempDir Path home) throws Exception {
        HistoryStore store = new HistoryStore(home, Duration.ofMinutes(1), 1);
        store.save(sample(), T0.plusSeconds(65));
        HistoryFile.Content loaded = store.load(T0.plusSeconds(65)).orElseThrow();
        assertEquals(1, loaded.node().size(), "the sample of T0 is older than a minute");
        assertEquals(T0.plusSeconds(10), loaded.node().get(0).at());
        assertEquals(List.of(new ServiceId("lobby-1")), List.copyOf(loaded.services().keySet()),
                "proxy-1 had only the old sample");
        assertTrue(store.load(T0.plusSeconds(3600)).orElseThrow().services().isEmpty());
    }

    @Test
    void storeMovesDamagedFilesAside(@TempDir Path home) throws Exception {
        HistoryStore store = new HistoryStore(home, Duration.ofHours(1), 100);
        Files.createDirectories(store.file().getParent());
        Files.write(store.file(), new byte[] {'V', 'M', 'H', 1, 0});
        try (Fakes.LogCapture log = new Fakes.LogCapture(HistoryStore.class.getName())) {
            assertTrue(store.load(T0).isEmpty());
            assertTrue(log.text().contains("is damaged"), log.text());
        }
        assertFalse(Files.exists(store.file()));
        try (var files = Files.list(store.file().getParent())) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("monitor-history.bin.broken-")));
        }
        assertTrue(store.load(T0).isEmpty(), "no file: empty, no error");
    }

    @Test
    void storeLeavesNonRegularFilesAlone(@TempDir Path home) throws Exception {
        HistoryStore store = new HistoryStore(home, Duration.ofHours(1), 100);
        Files.createDirectories(store.file());
        assertTrue(store.load(T0).isEmpty());
        store.save(sample(), T0);
        assertTrue(Files.isDirectory(store.file()), "not replaced");
    }
}
