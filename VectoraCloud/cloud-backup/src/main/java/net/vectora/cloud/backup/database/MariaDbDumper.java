package net.vectora.cloud.backup.database;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import net.vectora.cloud.backup.BackupException;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.RedactingFilter;

/**
 * Dumps the managed or external MariaDB/MySQL database with {@code mariadb-dump} (or
 * {@code mysqldump}) into one SQL file.
 *
 * <p>User, password, host and port go into an {@link OptionFile}; the command line only names that
 * file, so the password is never a process argument. The dump runs in one transaction
 * ({@code --single-transaction}, consistent for InnoDB, no table locks) and contains
 * {@code CREATE DATABASE} and {@code USE} ({@code --databases}), so it can be loaded as it is. The
 * managed mode dumps as root and adds routines, events and triggers.
 *
 * <p>A dump counts as complete only when the tool exits with 0 and the file ends with the
 * {@code -- Dump completed} line the tools write last.
 */
public final class MariaDbDumper {

    /** Names of the dump tools, in the order they are looked for. */
    public static final List<String> TOOL_NAMES = List.of("mariadb-dump", "mysqldump");

    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final String COMPLETED = "-- Dump completed";

    private final ToolRunner runner;
    private final Duration timeout;

    /**
     * Creates the dumper.
     *
     * @param runner runs the tool
     * @param timeout longest dump
     */
    public MariaDbDumper(ToolRunner runner, Duration timeout) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Finds the dump tool.
     *
     * <ol>
     *   <li>{@code configured} ({@code database.dump-executable} in {@code backup.yml}), if set;</li>
     *   <li>managed mode: {@code bin/mariadb-dump} or {@code bin/mysqldump} of the installed server;</li>
     *   <li>otherwise the folders of {@code PATH}.</li>
     * </ol>
     *
     * @param access the database
     * @param configured the configured tool, or null
     * @param path value of {@code PATH}, or null
     * @param windows whether to look for {@code .exe} files
     * @return the tool, if found
     */
    public static Optional<Path> findExecutable(DatabaseAccess access, Path configured, String path, boolean windows) {
        if (configured != null) {
            return Files.isRegularFile(configured) ? Optional.of(configured) : Optional.empty();
        }
        List<Path> folders = new ArrayList<>();
        if (access.mode() == DatabaseMode.MANAGED) {
            access.managedBaseFolder().ifPresent(base -> folders.add(base.resolve("bin")));
        } else if (path != null) {
            for (String part : path.split(File.pathSeparator)) {
                if (!part.isBlank()) {
                    try {
                        Path folder = Path.of(part.strip());
                        // Relative PATH entries would resolve against the working directory.
                        if (folder.isAbsolute()) {
                            folders.add(folder);
                        }
                    } catch (InvalidPathException ignored) {
                        // Not a usable folder.
                    }
                }
            }
        }
        for (String name : TOOL_NAMES) {
            for (Path folder : folders) {
                Path candidate = folder.resolve(windows ? name + ".exe" : name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The command line, without any secret.
     *
     * @param access the database
     * @param executable the tool
     * @param optionFile the option file with the credentials
     * @param resultFile where the dump goes
     * @return program and arguments
     */
    public static List<String> arguments(DatabaseAccess access, Path executable, Path optionFile, Path resultFile) {
        if (!DATABASE_NAME.matcher(access.database()).matches()) {
            throw new IllegalArgumentException("database name must match " + DATABASE_NAME.pattern());
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toAbsolutePath().toString());
        // Must be the first option; the tools ignore it anywhere else.
        command.add("--defaults-extra-file=" + optionFile.toAbsolutePath());
        command.add("--single-transaction");
        command.add("--quick");
        command.add("--skip-lock-tables");
        command.add("--no-tablespaces");
        command.add("--hex-blob");
        command.add("--default-character-set=utf8mb4");
        if (access.mode() == DatabaseMode.MANAGED) {
            command.add("--routines");
            command.add("--events");
            command.add("--triggers");
        }
        command.add("--result-file=" + resultFile.toAbsolutePath());
        command.add("--databases");
        command.add(access.database());
        return command;
    }

    /**
     * Dumps the database.
     *
     * @param access managed or external database
     * @param executable the dump tool
     * @param resultFile the SQL file to write; replaced if it exists
     * @param workDirectory folder for the private option-file folder
     * @throws BackupException with {@link BackupException.Reason#DATABASE} if the dump fails; the
     *     message is redacted
     * @throws InterruptedException if interrupted
     */
    public void dump(DatabaseAccess access, Path executable, Path resultFile, Path workDirectory)
            throws BackupException, InterruptedException {
        if (access.mode() != DatabaseMode.MANAGED && access.mode() != DatabaseMode.EXTERNAL) {
            throw new IllegalArgumentException("only managed and external databases are dumped with " + TOOL_NAMES);
        }
        RedactingFilter redact = RedactingFilter.of(List.of(access.password()));
        String tool = executable.getFileName().toString();
        OptionFile.removeLeftovers(workDirectory);
        ToolRunner.Result result;
        try {
            Files.deleteIfExists(resultFile);
            try (OptionFile option = OptionFile.create(workDirectory, access.user(), access.password(), access.host(),
                    access.port())) {
                result = runner.run(arguments(access, executable, option.file(), resultFile), workDirectory, timeout);
            }
        } catch (IllegalArgumentException e) {
            throw new BackupException(BackupException.Reason.DATABASE, e.getMessage());
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.DATABASE,
                    tool + " could not run: " + redact.redact(String.valueOf(e.getMessage())), e);
        }
        if (result.exitCode() != 0) {
            throw new BackupException(BackupException.Reason.DATABASE, tool + " failed with exit code "
                    + result.exitCode() + ": " + redact.redact(tail(result.output())));
        }
        try {
            if (!Files.isRegularFile(resultFile, LinkOption.NOFOLLOW_LINKS) || !endsWithCompleted(resultFile)) {
                throw new BackupException(BackupException.Reason.DATABASE,
                        tool + " ended without a complete dump (no \"" + COMPLETED + "\" line)");
            }
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.DATABASE, "cannot read the dump of " + tool, e);
        }
    }

    private static boolean endsWithCompleted(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, 1024);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(size - length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read the tail.
            }
            String tail = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            return tail.contains(COMPLETED);
        }
    }

    /** The last 500 characters of the output on one line. */
    static String tail(String output) {
        String flat = output.replaceAll("\\p{Cntrl}+", " ").strip();
        if (flat.isEmpty()) {
            return "(no output)";
        }
        return flat.length() > 500 ? flat.substring(flat.length() - 500) : flat;
    }
}
