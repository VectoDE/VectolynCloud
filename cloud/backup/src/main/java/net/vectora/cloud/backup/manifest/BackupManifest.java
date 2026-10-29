package net.vectora.cloud.backup.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.vectora.cloud.backup.BackupReason;

/**
 * {@code manifest.json}, the last entry of every backup archive: what was backed up, when, with
 * which versions, and the SHA-256 hash of every file. A restore trusts nothing in the archive that
 * the manifest does not list with a matching hash. Read with {@link ManifestCodec#read(byte[])},
 * which checks every rule below.
 *
 * @param schemaVersion version of the format, {@link #SCHEMA_VERSION}
 * @param id backup id, {@code yyyyMMdd-HHmmss-xxxx} (UTC time and four hex digits)
 * @param createdAt when the backup started, ISO-8601 instant
 * @param reason {@link BackupReason#id()}
 * @param cloudVersion version of VectoraCloud that wrote it ({@code dev} outside a release jar)
 * @param note free text, for example the version change it was made for; may be null
 * @param services the services it contains
 * @param database the database part; null when the backup has none
 * @param files every file with size and hash
 * @param skipped files left out because they could not be read or have a name that is not
 *     portable; may be empty
 */
@JsonPropertyOrder({"schema-version", "id", "created-at", "reason", "cloud-version", "note", "services", "database",
        "files", "skipped"})
public record BackupManifest(@JsonProperty(required = true) int schemaVersion,
                             @JsonProperty(required = true) String id,
                             @JsonProperty(required = true) String createdAt,
                             @JsonProperty(required = true) String reason,
                             @JsonProperty(required = true) String cloudVersion,
                             String note,
                             @JsonProperty(required = true) List<ServiceEntry> services,
                             DatabaseEntry database,
                             @JsonProperty(required = true) List<FileEntry> files,
                             List<SkippedEntry> skipped) {

    /** Current format version. */
    public static final int SCHEMA_VERSION = 1;
    /** Name of the manifest inside the archive. */
    public static final String ENTRY = "manifest.json";

    /** Copies the lists; a missing {@code skipped} list is empty. */
    public BackupManifest {
        services = services == null ? null : List.copyOf(services);
        files = files == null ? null : List.copyOf(files);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /** {@link #createdAt()} as an instant; only valid after {@link ManifestCodec#validate}. */
    public Instant created() {
        return Instant.parse(createdAt);
    }

    /** {@link #reason()} as an enum; only valid after {@link ManifestCodec#validate}. */
    public BackupReason reasonType() {
        return BackupReason.ofId(reason);
    }

    /** The entry of a service. */
    public Optional<ServiceEntry> service(String serviceId) {
        return services.stream().filter(s -> s.id().equals(serviceId)).findFirst();
    }

    /** Total size of all files in bytes. */
    public long totalBytes() {
        return files.stream().mapToLong(FileEntry::size).sum();
    }

    /**
     * One service in the backup.
     *
     * @param id service id
     * @param group group name
     * @param minecraftVersion Minecraft version of the group; empty when it has none
     * @param consistency how its files were taken, one of {@link Consistency}
     */
    @JsonPropertyOrder({"id", "group", "minecraft-version", "consistency"})
    public record ServiceEntry(@JsonProperty(required = true) String id,
                               @JsonProperty(required = true) String group,
                               @JsonProperty(required = true) String minecraftVersion,
                               @JsonProperty(required = true) String consistency) {

        /** Whether the backup holds files of it. */
        public boolean hasFiles() {
            return Consistency.of(consistency).hasFiles();
        }

        /** Prefix of its files in the archive: {@code services/<id>/}. */
        public String prefix() {
            return ManifestPaths.servicePrefix(id);
        }
    }

    /**
     * The database part.
     *
     * @param mode {@code managed}, {@code external}, {@code docker} or {@code h2}
     * @param status {@code dumped}, {@code per-service} (H2 dumps next to the service files),
     *     {@code manual} (Docker: dump by hand) or {@code failed}
     * @param file the dump in the archive ({@code database/<name>.sql}); null unless dumped
     * @param detail short explanation, for example why it failed; may be null
     */
    @JsonPropertyOrder({"mode", "status", "file", "detail"})
    public record DatabaseEntry(@JsonProperty(required = true) String mode,
                                @JsonProperty(required = true) String status,
                                String file, String detail) {
    }

    /**
     * One file.
     *
     * @param path path inside the archive, {@code /}-separated
     * @param size size in bytes
     * @param sha256 SHA-256, 64 lower-case hex digits
     */
    @JsonPropertyOrder({"path", "size", "sha256"})
    public record FileEntry(@JsonProperty(required = true) String path,
                            @JsonProperty(required = true) long size,
                            @JsonProperty(required = true) String sha256) {
    }

    /**
     * A file that was left out.
     *
     * @param path its path relative to the service folder, prefixed with {@code services/<id>/}
     * @param reason why
     */
    @JsonPropertyOrder({"path", "reason"})
    public record SkippedEntry(@JsonProperty(required = true) String path,
                               @JsonProperty(required = true) String reason) {
    }

    /** How the files of a service were taken. */
    public enum Consistency {
        /** Running server: {@code save-off}, {@code save-all flush}, copy, {@code save-on}. */
        SAVED("saved", true),
        /** Running server without worlds (proxy): copied while it runs. */
        LIVE("live", true),
        /** Stopped service: copied directly. */
        OFFLINE("offline", true),
        /** Left out: starting or stopping, or saving was not confirmed. */
        SKIPPED("skipped", false),
        /** The folder does not exist yet. */
        MISSING("missing", false);

        private final String id;
        private final boolean files;

        Consistency(String id, boolean files) {
            this.id = id;
            this.files = files;
        }

        /** The text in the manifest. */
        public String id() {
            return id;
        }

        /** Whether the archive holds files for it. */
        public boolean hasFiles() {
            return files;
        }

        /**
         * Parses {@link #id()}.
         *
         * @param id the text
         * @return the value
         * @throws IllegalArgumentException if unknown
         */
        public static Consistency of(String id) {
            for (Consistency value : values()) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown consistency");
        }
    }
}
