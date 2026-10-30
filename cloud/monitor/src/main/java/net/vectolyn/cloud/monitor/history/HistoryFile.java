package net.vectolyn.cloud.monitor.history;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.vectolyn.cloud.monitor.metric.NodeSample;
import net.vectolyn.cloud.monitor.metric.ServiceSample;
import net.vectolyn.protocol.ServiceId;

/**
 * Binary format of {@code state/monitor-history.bin}: compact (about 50 bytes per service sample)
 * and read strictly, because the file may be damaged or edited.
 *
 * <pre>
 * magic "VMH" + version 1                          4 bytes
 * node samples: count (int), then per sample
 *   second (long), cpu (float), memory used, memory total, disk free, disk total (long each)
 * services: count (int), then per service
 *   id length (unsigned byte) + id (ASCII), sample count (int), then per sample
 *   second (long), players online, max players (int each), tps, mspt (float each),
 *   heap used, heap max (long each), cpu (float), rss (long)
 * </pre>
 *
 * Big-endian. Unknown values are -1 (integers) or NaN (floats); other negative or infinite values,
 * times before 2000 or after 2100, invalid service ids, more than {@value #MAX_SERVICES} services
 * or {@value #MAX_SAMPLES} samples per series, and bytes after the end are errors. Jazzer fuzzes
 * the reader ({@code HistoryFileFuzzTest}).
 */
public final class HistoryFile {

    /** Most services in one file. */
    public static final int MAX_SERVICES = 10_000;

    /** Most samples per series. */
    public static final int MAX_SAMPLES = 100_000;

    /** Largest file read: 64 MiB. */
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    private static final byte[] MAGIC = {'V', 'M', 'H', 1};
    private static final long EARLIEST = 946_684_800L;   // 2000-01-01
    private static final long LATEST = 4_102_444_800L;   // 2100-01-01

    private HistoryFile() {
    }

    /**
     * The content of the file.
     *
     * @param node     node samples, oldest first
     * @param services samples per service, oldest first
     */
    public record Content(List<NodeSample> node, Map<ServiceId, List<ServiceSample>> services) {

        /** Copies the lists. */
        public Content {
            node = List.copyOf(node);
            Map<ServiceId, List<ServiceSample>> copy = new LinkedHashMap<>();
            services.forEach((id, list) -> copy.put(Objects.requireNonNull(id), List.copyOf(list)));
            services = java.util.Collections.unmodifiableMap(copy);
        }
    }

    /** The file is damaged. */
    public static final class FormatException extends IOException {

        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }
    }

    /**
     * Encodes the content. Values are stored as floats where the format says so, so a sample read
     * back may differ from the original in the last digits.
     *
     * @param content what to write
     * @return the bytes
     */
    public static byte[] encode(Content content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MAGIC);
            List<NodeSample> node = tail(content.node());
            out.writeInt(node.size());
            for (NodeSample s : node) {
                out.writeLong(s.at().getEpochSecond());
                out.writeFloat((float) s.cpu());
                out.writeLong(s.memoryUsed());
                out.writeLong(s.memoryTotal());
                out.writeLong(s.diskFree());
                out.writeLong(s.diskTotal());
            }
            List<Map.Entry<ServiceId, List<ServiceSample>>> services = new ArrayList<>(content.services().entrySet());
            if (services.size() > MAX_SERVICES) {
                services = services.subList(0, MAX_SERVICES);
            }
            out.writeInt(services.size());
            for (Map.Entry<ServiceId, List<ServiceSample>> entry : services) {
                byte[] id = entry.getKey().value().getBytes(StandardCharsets.US_ASCII);
                out.writeByte(id.length);
                out.write(id);
                List<ServiceSample> samples = tail(entry.getValue());
                out.writeInt(samples.size());
                for (ServiceSample s : samples) {
                    out.writeLong(s.at().getEpochSecond());
                    out.writeInt(s.playersOnline());
                    out.writeInt(s.maxPlayers());
                    out.writeFloat((float) s.tps());
                    out.writeFloat((float) s.mspt());
                    out.writeLong(s.heapUsed());
                    out.writeLong(s.heapMax());
                    out.writeFloat((float) s.cpu());
                    out.writeLong(s.rss());
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> List<T> tail(List<T> list) {
        return list.size() <= MAX_SAMPLES ? list : list.subList(list.size() - MAX_SAMPLES, list.size());
    }

    /**
     * Decodes a file.
     *
     * @param data the bytes
     * @return the content
     * @throws FormatException if the bytes break a rule of the format
     */
    public static Content decode(byte[] data) throws FormatException {
        if (data.length > MAX_BYTES) {
            throw new FormatException("history file is larger than " + MAX_BYTES + " bytes");
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        try {
            for (byte b : MAGIC) {
                if (in.get() != b) {
                    throw new FormatException("not a history file of version 1");
                }
            }
            int nodeCount = count(in.getInt(), MAX_SAMPLES, "node samples");
            List<NodeSample> node = new ArrayList<>(Math.min(nodeCount, 1024));
            for (int i = 0; i < nodeCount; i++) {
                Instant at = second(in.getLong());
                double cpu = rate(in.getFloat());
                long used = bytes(in.getLong());
                long total = bytes(in.getLong());
                long free = bytes(in.getLong());
                long disk = bytes(in.getLong());
                if (total == 0 || disk == 0) {
                    throw new FormatException("a total of 0 bytes is stored as -1");
                }
                node.add(new NodeSample(at, cpu, used, total, free, disk));
            }
            int serviceCount = count(in.getInt(), MAX_SERVICES, "services");
            Map<ServiceId, List<ServiceSample>> services = new LinkedHashMap<>();
            for (int s = 0; s < serviceCount; s++) {
                int length = Byte.toUnsignedInt(in.get());
                byte[] raw = new byte[length];
                in.get(raw);
                ServiceId id;
                try {
                    id = new ServiceId(new String(raw, StandardCharsets.US_ASCII));
                } catch (IllegalArgumentException e) {
                    throw new FormatException("invalid service id in history file");
                }
                if (services.containsKey(id)) {
                    throw new FormatException("service listed twice in history file");
                }
                int sampleCount = count(in.getInt(), MAX_SAMPLES, "service samples");
                List<ServiceSample> samples = new ArrayList<>(Math.min(sampleCount, 1024));
                for (int i = 0; i < sampleCount; i++) {
                    Instant at = second(in.getLong());
                    int online = number(in.getInt());
                    int max = number(in.getInt());
                    double tps = rate(in.getFloat());
                    double mspt = rate(in.getFloat());
                    long heapUsed = bytes(in.getLong());
                    long heapMax = bytes(in.getLong());
                    double cpu = rate(in.getFloat());
                    long rss = bytes(in.getLong());
                    if (heapMax == 0) {
                        throw new FormatException("a heap maximum of 0 is stored as -1");
                    }
                    samples.add(new ServiceSample(at, online, max, tps, mspt, heapUsed, heapMax, cpu, rss));
                }
                services.put(id, samples);
            }
            if (in.hasRemaining()) {
                throw new FormatException("bytes after the end of the history file");
            }
            return new Content(node, services);
        } catch (BufferUnderflowException e) {
            throw new FormatException("history file ends early");
        }
    }

    private static int count(int value, int max, String what) throws FormatException {
        if (value < 0 || value > max) {
            throw new FormatException("number of " + what + " must be 0 to " + max);
        }
        return value;
    }

    private static Instant second(long value) throws FormatException {
        if (value < EARLIEST || value > LATEST) {
            throw new FormatException("sample time outside 2000 to 2100");
        }
        return Instant.ofEpochSecond(value);
    }

    private static int number(int value) throws FormatException {
        if (value < -1) {
            throw new FormatException("negative count other than -1");
        }
        return value;
    }

    private static long bytes(long value) throws FormatException {
        if (value < -1) {
            throw new FormatException("negative byte value other than -1");
        }
        return value;
    }

    private static double rate(float value) throws FormatException {
        if (Float.isNaN(value)) {
            return Double.NaN;
        }
        if (Float.isInfinite(value) || value < 0 || value > 1_000_000f) {
            throw new FormatException("rate outside 0 to 1000000");
        }
        return value;
    }
}
