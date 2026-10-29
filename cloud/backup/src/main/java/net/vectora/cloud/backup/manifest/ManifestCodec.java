package net.vectora.cloud.backup.manifest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectora.cloud.backup.BackupReason;
import net.vectora.cloud.backup.BackupTarget;
import net.vectora.cloud.backup.manifest.BackupManifest.Consistency;
import net.vectora.cloud.backup.manifest.BackupManifest.DatabaseEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.FileEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.ServiceEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.SkippedEntry;
import net.vectora.cloud.config.io.ConfigMappers;
import net.vectora.cloud.config.validation.ConfigNames;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and strictly reads {@code manifest.json}.
 *
 * <p>The manifest comes from a file anyone with access to the backup folder may have edited, so
 * {@link #read(byte[])} assumes nothing: size, nesting, string lengths and token count are capped
 * while parsing, unknown fields and duplicate keys fail (the strict Jackson setup of cloud-config),
 * and {@link #validate} then checks every value. The only exception it throws is
 * {@link ManifestException}; {@code ManifestCodecFuzzTest} fuzzes exactly that.
 */
public final class ManifestCodec {

    /** Largest manifest, 128 MiB. */
    public static final int MAX_BYTES = 128 * 1024 * 1024;
    /** Most files in one backup. */
    public static final int MAX_FILES = 500_000;
    /** Most services in one backup. */
    public static final int MAX_SERVICES = 1000;
    /** Largest single file, 1 TiB. */
    public static final long MAX_FILE_SIZE = 1L << 40;

    /** Backup id: UTC time and four hex digits. */
    public static final String ID = "[0-9]{8}-[0-9]{6}-[0-9a-f]{4}";

    private static final Pattern ID_PATTERN = Pattern.compile(ID);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+-]{1,64}");
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("[0-9A-Za-z._+-]{0,64}");
    private static final Pattern GROUP = Pattern.compile(ConfigNames.NAME);
    private static final Set<String> MODES = Set.of("managed", "external", "docker", "h2");
    private static final Set<String> STATUSES = Set.of("dumped", "per-service", "manual", "failed");

    private static final JsonMapper MAPPER = ConfigMappers.configure(JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(8)
                            .maxStringLength(8 * 1024)
                            .maxNameLength(64)
                            .maxNumberLength(20)
                            .maxDocumentLength(MAX_BYTES)
                            .maxTokenCount(12L * MAX_FILES)
                            .build())
                    .build()))
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private ManifestCodec() {
    }

    /**
     * Serializes a manifest as UTF-8 JSON.
     *
     * @param manifest a valid manifest
     * @return the bytes
     * @throws IllegalArgumentException if it breaks a rule (a bug in the writer)
     */
    public static byte[] write(BackupManifest manifest) {
        try {
            validate(manifest);
        } catch (ManifestException e) {
            throw new IllegalArgumentException("refusing to write an invalid manifest: " + e.getMessage(), e);
        }
        return MAPPER.writeValueAsBytes(manifest);
    }

    /**
     * Parses and checks a manifest.
     *
     * @param content the raw bytes
     * @return the manifest
     * @throws ManifestException if it is broken or breaks a rule
     */
    public static BackupManifest read(byte[] content) throws ManifestException {
        if (content == null || content.length == 0) {
            throw new ManifestException("manifest is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new ManifestException("manifest is larger than " + MAX_BYTES + " bytes");
        }
        BackupManifest manifest;
        try {
            manifest = MAPPER.readValue(content, BackupManifest.class);
        } catch (RuntimeException e) {
            // Jackson 3 exceptions are unchecked; their messages may quote the content, so only the type is passed on.
            throw new ManifestException("manifest is not valid JSON of the expected shape ("
                    + e.getClass().getSimpleName() + ")");
        }
        if (manifest == null) {
            throw new ManifestException("manifest is empty");
        }
        validate(manifest);
        return manifest;
    }

    /**
     * Checks every rule of a manifest.
     *
     * @param m the manifest
     * @throws ManifestException at the first broken rule
     */
    public static void validate(BackupManifest m) throws ManifestException {
        require(m.schemaVersion() == BackupManifest.SCHEMA_VERSION, "unsupported schema-version");
        require(m.id() != null && ID_PATTERN.matcher(m.id()).matches(), "id must match " + ID);
        require(m.createdAt() != null && m.createdAt().length() <= 40, "created-at is missing or too long");
        try {
            Instant.parse(m.createdAt());
        } catch (DateTimeParseException e) {
            throw new ManifestException("created-at is not an ISO-8601 instant");
        }
        try {
            BackupReason.ofId(String.valueOf(m.reason()));
        } catch (IllegalArgumentException e) {
            throw new ManifestException("unknown reason");
        }
        require(m.cloudVersion() != null && VERSION.matcher(m.cloudVersion()).matches(), "invalid cloud-version");
        require(m.note() == null || isText(m.note(), 300), "note is too long or has control characters");

        require(m.services() != null, "services is missing");
        require(m.services().size() <= MAX_SERVICES, "more than " + MAX_SERVICES + " services");
        Map<String, ServiceEntry> services = new HashMap<>();
        for (ServiceEntry service : m.services()) {
            require(service != null, "null service entry");
            require(BackupTarget.isServiceId(service.id()), "service id must match " + BackupTarget.SERVICE_ID);
            require(service.group() != null && GROUP.matcher(service.group()).matches(),
                    "service group must match " + ConfigNames.NAME);
            require(service.minecraftVersion() != null && MINECRAFT_VERSION.matcher(service.minecraftVersion()).matches(),
                    "invalid minecraft-version");
            try {
                Consistency.of(String.valueOf(service.consistency()));
            } catch (IllegalArgumentException e) {
                throw new ManifestException("unknown consistency");
            }
            require(services.put(service.id(), service) == null, "service listed twice");
        }

        require(m.files() != null, "files is missing");
        require(m.files().size() <= MAX_FILES, "more than " + MAX_FILES + " files");
        Set<String> paths = new HashSet<>();
        for (FileEntry file : m.files()) {
            require(file != null, "null file entry");
            require(ManifestPaths.isSafe(file.path()), "unsafe file path");
            require(file.size() >= 0 && file.size() <= MAX_FILE_SIZE, "file size out of range");
            require(file.sha256() != null && SHA256.matcher(file.sha256()).matches(),
                    "sha256 must be 64 lower-case hex digits");
            require(paths.add(ManifestPaths.foldKey(file.path())), "file listed twice (also case-insensitively)");
            require(!file.path().equals(BackupManifest.ENTRY), "a file must not replace the manifest");
            if (file.path().startsWith(ManifestPaths.SERVICES)) {
                String rest = file.path().substring(ManifestPaths.SERVICES.length());
                int slash = rest.indexOf('/');
                require(slash > 0 && slash < rest.length() - 1, "service file without a service folder");
                ServiceEntry owner = services.get(rest.substring(0, slash));
                require(owner != null, "file of a service the manifest does not list");
                require(owner.hasFiles(), "file of a service marked as skipped or missing");
            } else {
                require(file.path().startsWith(ManifestPaths.DATABASE)
                        && file.path().indexOf('/', ManifestPaths.DATABASE.length()) < 0,
                        "files must lie in services/<id>/ or database/");
            }
        }

        DatabaseEntry database = m.database();
        if (database != null) {
            require(MODES.contains(String.valueOf(database.mode())), "unknown database mode");
            require(STATUSES.contains(String.valueOf(database.status())), "unknown database status");
            require(database.detail() == null || isText(database.detail(), 500),
                    "database detail is too long or has control characters");
            if ("dumped".equals(database.status())) {
                require(database.file() != null && database.file().startsWith(ManifestPaths.DATABASE)
                        && paths.contains(ManifestPaths.foldKey(database.file())), "database file is not listed");
            } else {
                require(database.file() == null, "database file without a dump");
            }
        }

        for (SkippedEntry skipped : m.skipped()) {
            require(skipped != null, "null skipped entry");
            require(skipped.path() != null && isText(skipped.path(), ManifestPaths.MAX_LENGTH), "invalid skipped path");
            require(skipped.reason() != null && isText(skipped.reason(), 300), "invalid skipped reason");
        }
    }

    private static boolean isText(String value, int max) {
        if (value.length() > max) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String rule) throws ManifestException {
        if (!condition) {
            throw new ManifestException(rule);
        }
    }
}
