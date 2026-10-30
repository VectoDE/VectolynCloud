package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.io.RegularPaths;

/**
 * The small state files of the mirror under {@code state/cluster/}, written atomically:
 *
 * <pre>
 * config-revision     head:   revision and digest of the config it mirrors
 * config-applied      member: revision and digest of the config it took over last
 * templates           member: name and digest of every template it synced
 * </pre>
 *
 * A file that cannot be read counts as missing; the mirror then starts over, which costs a
 * download but never trusts a broken record.
 */
final class MirrorState {

    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern REVISION = Pattern.compile("revision=([0-9]{1,18})\ndigest=([0-9a-f]{64})\n");
    private static final Pattern TEMPLATE_LINE = Pattern.compile("([^ \n]{1,64}) ([0-9a-f]{64})");
    private static final HexFormat HEX = HexFormat.of();

    private MirrorState() {
    }

    /**
     * A revision and the digest of its files.
     *
     * @param revision the revision
     * @param digest   32 bytes
     */
    record Revision(long revision, byte[] digest) {

        Revision {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        boolean sameDigest(byte[] other) {
            return MessageDigest.isEqual(digest, other);
        }
    }

    static Optional<Revision> readRevision(Path file) throws IOException {
        Optional<String> text = read(file);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = REVISION.matcher(text.get());
        if (!m.matches()) {
            throw new IOException(file.getFileName() + " is not valid");
        }
        return Optional.of(new Revision(Long.parseLong(m.group(1)), HEX.parseHex(m.group(2))));
    }

    static void writeRevision(Path file, Revision value) throws IOException {
        write(file, "revision=" + value.revision() + "\ndigest=" + HEX.formatHex(value.digest()) + "\n");
    }

    static Map<String, byte[]> readTemplates(Path file) throws IOException {
        Map<String, byte[]> templates = new TreeMap<>();
        Optional<String> text = read(file);
        if (text.isEmpty()) {
            return templates;
        }
        for (String line : text.get().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = TEMPLATE_LINE.matcher(line);
            if (!m.matches() || !MirrorPaths.isTemplateName(m.group(1)) || templates.size() >= MirrorPaths.MAX_TEMPLATES) {
                throw new IOException(file.getFileName() + " is not valid");
            }
            templates.put(m.group(1), HEX.parseHex(m.group(2)));
        }
        return templates;
    }

    static void writeTemplates(Path file, Map<String, byte[]> templates) throws IOException {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(templates).forEach((name, digest) -> text.append(name).append(' ')
                .append(HEX.formatHex(digest)).append('\n'));
        write(file, text.toString());
    }

    private static Optional<String> read(Path file) throws IOException {
        RegularPaths.requireFileOrMissing(file);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException(file.getFileName() + " is too large");
        }
        return Optional.of(Files.readString(file, StandardCharsets.US_ASCII));
    }

    private static void write(Path file, String text) throws IOException {
        RegularPaths.requireDirectoryOrMissing(file.getParent());
        RegularPaths.requireFileOrMissing(file);
        new AtomicFileWriter().write(file, text.getBytes(StandardCharsets.US_ASCII));
    }
}
