package net.vectora.cloud.config.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.io.AtomicFileWriter;

/**
 * Reads and writes {@code secrets/vectora.env}.
 *
 * <ul>
 *   <li>Existing values are never overwritten. Only missing keys and empty entries ({@code KEY=},
 *       as in the example file) are filled.</li>
 *   <li>Comments and blank lines are kept; new keys are appended with a short comment.</li>
 *   <li>The file is replaced atomically; the temporary file is restricted to the owner before the
 *       secrets are written into it. Folder and file are owner-only afterwards
 *       ({@link OwnerOnlyPermissions}).</li>
 * </ul>
 *
 * <p>Thread-safe within one process. Two processes writing the file at the same moment are not
 * coordinated; the cloud is the only writer.
 */
public final class SecretStore {

    private final Path file;
    private final AtomicFileWriter writer;

    /** Store for {@code <home>/secrets/vectora.env}. */
    public SecretStore(CloudHome home) {
        this(home.secretsFile());
    }

    /** Store for {@code file}. */
    public SecretStore(Path file) {
        this(file, new AtomicFileWriter());
    }

    /** Store with a custom writer (tests). */
    public SecretStore(Path file, AtomicFileWriter writer) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** The file. */
    public Path file() {
        return file;
    }

    /** The current content; empty if the file does not exist yet. */
    public synchronized EnvFile load() throws IOException, EnvFormatException {
        try {
            if (Files.size(file) > EnvFileParser.MAX_BYTES) {
                throw new EnvFormatException(0, EnvFormatException.Reason.TOO_LARGE);
            }
            return EnvFileParser.parse(Files.readAllBytes(file));
        } catch (NoSuchFileException e) {
            return EnvFile.empty();
        }
    }

    /**
     * Generates every {@link GeneratedSecret} that has no value yet and writes the file if anything
     * was added. Also restricts folder and file to the owner, even when nothing was added.
     *
     * @return keys that were generated now; empty if all existed
     */
    public synchronized Set<String> ensureGenerated(SecretGenerator generator) throws IOException, EnvFormatException {
        EnvFile content = load();
        Set<String> generated = new LinkedHashSet<>();
        for (GeneratedSecret kind : GeneratedSecret.values()) {
            if (!content.hasValue(kind.key())) {
                content = content.withValueIfAbsent(kind.key(), generator.generate(kind), kind.description());
                generated.add(kind.key());
            }
        }
        if (!generated.isEmpty()) {
            write(content);
        } else if (Files.exists(file)) {
            OwnerOnlyPermissions.restrictDirectory(file.getParent());
            OwnerOnlyPermissions.restrictFile(file);
        }
        return generated;
    }

    /**
     * Sets {@code key} to {@code value} unless it already has a value.
     *
     * @return whether the value was written
     * @throws IllegalArgumentException if the key or value cannot be stored (message without the value)
     */
    public synchronized boolean putIfAbsent(String key, Secret value) throws IOException, EnvFormatException {
        EnvFile content = load();
        if (content.hasValue(key)) {
            return false;
        }
        write(content.withValueIfAbsent(key, value, null));
        return true;
    }

    /** A {@link RedactingFilter} for every value in the file. */
    public RedactingFilter redactingFilter() throws IOException, EnvFormatException {
        return RedactingFilter.of(load());
    }

    private void write(EnvFile content) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        OwnerOnlyPermissions.restrictDirectory(dir);
        writer.write(file, content.render().getBytes(StandardCharsets.UTF_8), OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(file);
    }

    @Override
    public String toString() {
        return "SecretStore[" + file + "]";
    }
}
