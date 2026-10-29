package net.vectora.cloud.node.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.io.ConfigMappers;
import net.vectora.cloud.config.validation.ConfigNames;
import net.vectora.cloud.node.service.ServiceIds;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes {@code <home>/state/services.json}.
 *
 * <p>The file is written atomically through {@link AtomicFileWriter} (temporary file in the same
 * folder, flushed, renamed over the old file), so a crash of the cloud never leaves half a file.
 * Reading is strict, with the same rules as the configuration files (unknown fields, duplicate
 * keys and wrong types are errors), and every value is checked: names follow the config rules,
 * ids follow {@code <group>-<index>}, and the folder is a plain relative path inside the home.
 * The file is untrusted input as far as the node is concerned; whatever is in it, the node only
 * ever acts on a process whose pid and start time both match (see {@code OrphanRecovery}) and only
 * deletes folders below {@code <home>/temp/services/}.
 */
public final class ServiceStateStore {

    /** Folder of the state files, relative to the home. */
    public static final String STATE_DIR = "state";
    /** File name of the service state. */
    public static final String FILE_NAME = "services.json";
    /** Upper limit for the file, 4 MiB. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    /** Upper limit for the number of services in the file. */
    public static final int MAX_SERVICES = 10_000;

    private static final JsonMapper JSON = ConfigMappers.configure(JsonMapper.builder())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();
    private static final Pattern RELATIVE_PATH = Pattern.compile(ConfigNames.RELATIVE_PATH);

    private final Path file;
    private final AtomicFileWriter writer;

    /**
     * Creates a store for {@code <home>/state/services.json}.
     *
     * @param home the cloud home
     */
    public ServiceStateStore(Path home) {
        this(home.resolve(STATE_DIR).resolve(FILE_NAME), new AtomicFileWriter());
    }

    /**
     * Creates a store for a given file.
     *
     * @param file   the state file
     * @param writer the atomic writer
     */
    public ServiceStateStore(Path file, AtomicFileWriter writer) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Returns the path of the state file.
     *
     * @return the file
     */
    public Path file() {
        return file;
    }

    /**
     * Reads the file.
     *
     * @return the state, or empty if there is no file yet
     * @throws IOException        if the file cannot be read
     * @throws StateFileException if its content is invalid
     */
    public Optional<ServiceStates> load() throws IOException, StateFileException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new StateFileException(FILE_NAME + " is not a regular file");
        }
        if (Files.size(file) > MAX_BYTES) {
            throw new StateFileException(FILE_NAME + " is larger than " + MAX_BYTES + " bytes");
        }
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        return Optional.of(parse(content));
    }

    /**
     * Writes the file atomically, creating {@code state/} if needed.
     *
     * @param states the state
     * @throws IOException if writing fails
     */
    public void save(ServiceStates states) throws IOException {
        Files.createDirectories(file.getParent());
        writer.write(file, write(states));
    }

    /**
     * Moves a broken file aside as {@code services.json.broken-<millis>} so the node can start
     * with an empty state and an operator can still look at it.
     *
     * @param now current time
     * @return the new name, or empty if there was no file
     * @throws IOException if the move fails
     */
    public Optional<Path> moveAside(Instant now) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path target = file.resolveSibling(FILE_NAME + ".broken-" + now.toEpochMilli());
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(target);
    }

    /**
     * Parses and checks the content of a state file.
     *
     * @param content the bytes, UTF-8 JSON
     * @return the checked state
     * @throws StateFileException if the content is not a valid state
     */
    public static ServiceStates parse(byte[] content) throws StateFileException {
        if (content.length > MAX_BYTES) {
            throw new StateFileException(FILE_NAME + " is larger than " + MAX_BYTES + " bytes");
        }
        ServiceStates states;
        try {
            states = JSON.readValue(content, ServiceStates.class);
        } catch (JacksonException e) {
            throw new StateFileException(FILE_NAME + " is not valid: " + describe(e), e);
        }
        if (states == null) {
            throw new StateFileException(FILE_NAME + " is empty");
        }
        check(states);
        return states;
    }

    /**
     * Serializes a state.
     *
     * @param states the state, checked first
     * @return UTF-8 JSON with a trailing line break
     * @throws IllegalArgumentException if the state would not pass {@link #parse(byte[])}
     */
    public static byte[] write(ServiceStates states) {
        try {
            check(states);
        } catch (StateFileException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        byte[] json = JSON.writeValueAsBytes(states);
        byte[] out = new byte[json.length + 1];
        System.arraycopy(json, 0, out, 0, json.length);
        out[json.length] = '\n';
        return out;
    }

    private static void check(ServiceStates states) throws StateFileException {
        if (states.schemaVersion() != ServiceStates.SCHEMA_VERSION) {
            throw new StateFileException("unsupported schema-version, expected " + ServiceStates.SCHEMA_VERSION);
        }
        if (states.node() == null || !ConfigNames.isValidName(states.node())) {
            throw new StateFileException("node must match " + ConfigNames.NAME);
        }
        if (states.services().size() > MAX_SERVICES) {
            throw new StateFileException("more than " + MAX_SERVICES + " services");
        }
        Set<String> ids = new HashSet<>();
        Set<String> folders = new HashSet<>();
        for (int i = 0; i < states.services().size(); i++) {
            ServiceRecord r = states.services().get(i);
            String at = "services[" + i + "]";
            if (r == null) {
                throw new StateFileException(at + " is null");
            }
            if (r.group() == null || !ConfigNames.isValidName(r.group())
                    || r.task() == null || !ConfigNames.isValidName(r.task())) {
                throw new StateFileException(at + ": group and task must match " + ConfigNames.NAME);
            }
            if (r.index() < 1 || r.index() > ServiceIds.MAX_INDEX
                    || r.id() == null || !r.id().equals(ServiceIds.of(r.group(), r.index()).value())) {
                throw new StateFileException(at + ": id must be <group>-<index> with index 1 to " + ServiceIds.MAX_INDEX);
            }
            if (!ids.add(r.id())) {
                throw new StateFileException(at + ": duplicate id");
            }
            if (r.mode() == null || r.state() == null) {
                throw new StateFileException(at + ": mode and state are required");
            }
            if (r.pid() < 0 || r.startedAt() < 0) {
                throw new StateFileException(at + ": pid and started-at must not be negative");
            }
            if (r.port() < 1 || r.port() > 65535) {
                throw new StateFileException(at + ": port must be between 1 and 65535");
            }
            if (r.workDirectory() == null || !RELATIVE_PATH.matcher(r.workDirectory()).matches()) {
                throw new StateFileException(at + ": work-directory must be a relative path matching "
                        + ConfigNames.RELATIVE_PATH);
            }
            if (!folders.add(r.workDirectory())) {
                throw new StateFileException(at + ": two services share one work-directory");
            }
        }
    }

    /** Names the problem without quoting the content. */
    private static String describe(JacksonException e) {
        String type = e.getClass().getSimpleName();
        String path = e.getPath().isEmpty() ? "" : " at " + pathOf(e);
        return type + path;
    }

    private static String pathOf(JacksonException e) {
        StringBuilder out = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                String name = ConfigNames.safeArgument(ref.getPropertyName());
                out.append(out.isEmpty() ? "" : ".").append(name);
            } else if (ref.getIndex() >= 0) {
                out.append('[').append(ref.getIndex()).append(']');
            }
        }
        return out.toString();
    }
}
