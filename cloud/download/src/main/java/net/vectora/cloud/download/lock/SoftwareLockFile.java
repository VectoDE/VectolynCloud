package net.vectora.cloud.download.lock;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.vectora.cloud.download.hash.Checksum;
import net.vectora.cloud.download.hash.HashAlgorithm;
import net.vectora.cloud.download.provider.ArtifactCoordinate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Reads and writes {@code software.lock.yml}. The format is described in docs/cloud/download.md.
 *
 * <p>Reading is strict: unknown keys, duplicate keys, unquoted versions and invalid hashes are
 * errors, because a lockfile that is read differently than it was meant is worse than none.
 * Writing goes through a temporary file and an atomic move, so a crash never leaves half a file.
 */
public final class SoftwareLockFile {

    /** File name inside {@link #CONFIG_DIR}. */
    public static final String FILE_NAME = "software.lock.yml";

    /** Folder of the file below the cloud home (the same as {@code CloudHome.CONFIG_DIR}). */
    public static final String CONFIG_DIR = "config";

    static final String HEADER = "# Written by VectoraCloud. Pins every downloaded artifact to one file and its hash.\n"
            + "# Format: docs/cloud/download.md. Do not edit while the cloud is running.\n";

    private static final Set<String> ROOT_KEYS = Set.of("schema-version", "artifacts");
    private static final Set<String> ENTRY_KEYS = Set.of("provider", "project", "version", "build", "url",
            "file-name", "hash-type", "hash", "sha256", "trust", "size", "previous");

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

    private SoftwareLockFile() {
    }

    /**
     * Reads a lockfile. A missing file is an empty lock.
     *
     * @param file the path of {@code software.lock.yml}
     * @return the lock
     * @throws LockFileException if the content is invalid
     * @throws IOException if the file cannot be read
     */
    public static SoftwareLock read(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return SoftwareLock.empty();
        }
        try {
            return parse(text);
        } catch (LockFileException e) {
            throw new LockFileException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes a lockfile atomically.
     *
     * @param file the path of {@code software.lock.yml}
     * @param lock the lock
     * @throws IOException if writing fails; the old file is then unchanged
     */
    public static void write(Path file, SoftwareLock lock) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path directory = absolute.getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, FILE_NAME + ".", ".tmp");
        try {
            Files.writeString(temp, format(lock), StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Renders a lock as YAML, including the header comment.
     *
     * @param lock the lock
     * @return the file content
     */
    public static String format(SoftwareLock lock) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schema-version", SoftwareLock.SCHEMA_VERSION);
        ObjectNode artifacts = root.putObject("artifacts");
        lock.entries().forEach((name, entry) -> artifacts.set(name, toNode(entry)));
        return HEADER + MAPPER.writeValueAsString(root);
    }

    private static ObjectNode toNode(LockedArtifact entry) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArtifactCoordinate c = entry.coordinate();
        node.put("provider", c.provider());
        node.put("project", c.project());
        node.put("version", c.version());
        if (c.build() != null) {
            node.put("build", c.build());
        }
        node.put("url", entry.url().toString());
        node.put("file-name", entry.fileName());
        node.put("hash-type", entry.checksum().algorithm().id());
        node.put("hash", entry.checksum().hex());
        node.put("sha256", entry.sha256().hex());
        node.put("trust", entry.trust().id());
        node.put("size", entry.size());
        if (entry.previous() != null) {
            node.set("previous", toNode(entry.previous()));
        }
        return node;
    }

    /**
     * Parses lockfile content.
     *
     * @param yaml the content
     * @return the lock
     * @throws LockFileException if the content is invalid
     */
    public static SoftwareLock parse(String yaml) throws LockFileException {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (JacksonException e) {
            throw new LockFileException("invalid YAML (" + e.getOriginalMessage() + ")", e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return SoftwareLock.empty();
        }
        if (!root.isObject()) {
            throw new LockFileException("expected a mapping at the top level");
        }
        checkKeys(root, ROOT_KEYS, "top level");
        JsonNode schema = root.get("schema-version");
        if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()) {
            throw new LockFileException("schema-version is missing or not a number");
        }
        if (schema.intValue() != SoftwareLock.SCHEMA_VERSION) {
            throw new LockFileException("schema-version " + schema.intValue() + " is not supported, this cloud reads "
                    + SoftwareLock.SCHEMA_VERSION);
        }
        JsonNode artifacts = root.get("artifacts");
        Map<String, LockedArtifact> entries = new LinkedHashMap<>();
        if (artifacts != null && !artifacts.isNull()) {
            if (!artifacts.isObject()) {
                throw new LockFileException("artifacts must be a mapping");
            }
            for (Map.Entry<String, JsonNode> entry : artifacts.properties()) {
                String name = entry.getKey();
                if (!SoftwareLock.NAME.matcher(name).matches()) {
                    throw new LockFileException("artifacts." + name + ": invalid name, must match "
                            + SoftwareLock.NAME.pattern());
                }
                entries.put(name, toEntry(entry.getValue(), "artifacts." + name, true));
            }
        }
        return SoftwareLock.of(entries);
    }

    private static LockedArtifact toEntry(JsonNode node, String context, boolean allowPrevious)
            throws LockFileException {
        if (node == null || !node.isObject()) {
            throw new LockFileException(context + ": expected a mapping");
        }
        checkKeys(node, ENTRY_KEYS, context);
        HashAlgorithm algorithm = HashAlgorithm.fromId(string(node, "hash-type", context))
                .orElseThrow(() -> new LockFileException(context + ".hash-type: unknown, use sha1, sha256 or sha512"));
        Checksum checksum = checksum(algorithm, string(node, "hash", context), context + ".hash");
        Checksum sha256 = checksum(HashAlgorithm.SHA256, string(node, "sha256", context), context + ".sha256");
        Trust trust = Trust.fromId(string(node, "trust", context))
                .orElseThrow(() -> new LockFileException(context + ".trust: must be source or tofu"));
        JsonNode size = node.get("size");
        if (size == null || !size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 0) {
            throw new LockFileException(context + ".size: expected a non-negative number");
        }
        String rawUrl = string(node, "url", context);
        URI url;
        try {
            url = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new LockFileException(context + ".url: malformed", e);
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            throw new LockFileException(context + ".url: must be an https URL");
        }
        LockedArtifact previous = null;
        JsonNode previousNode = node.get("previous");
        if (previousNode != null && !previousNode.isNull()) {
            if (!allowPrevious) {
                throw new LockFileException(context + ".previous: only one level of previous is kept");
            }
            previous = toEntry(previousNode, context + ".previous", false);
        }
        try {
            String build = node.has("build") && !node.get("build").isNull() ? string(node, "build", context) : null;
            ArtifactCoordinate coordinate = new ArtifactCoordinate(string(node, "provider", context),
                    string(node, "project", context), string(node, "version", context), build);
            return new LockedArtifact(coordinate, url, string(node, "file-name", context), checksum, sha256, trust,
                    size.longValue(), previous);
        } catch (IllegalArgumentException e) {
            throw new LockFileException(context + ": " + e.getMessage(), e);
        }
    }

    private static void checkKeys(JsonNode node, Set<String> allowed, String context) throws LockFileException {
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                throw new LockFileException(context + ": unknown key '" + key + "'");
            }
        }
    }

    private static String string(JsonNode node, String field, String context) throws LockFileException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LockFileException(context + "." + field + ": missing");
        }
        if (!value.isString()) {
            throw new LockFileException(context + "." + field + ": must be a quoted string");
        }
        return value.stringValue();
    }

    private static Checksum checksum(HashAlgorithm algorithm, String hex, String context) throws LockFileException {
        return Checksum.parse(algorithm, hex).orElseThrow(() -> new LockFileException(context + ": not a valid "
                + algorithm.id() + " value"));
    }
}
