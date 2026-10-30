package net.vectolyn.cloud.backup.database;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.backup.BackupException;

/**
 * Dumps the embedded H2 database of LuckPerms ({@code database.mode: h2}) with H2's own
 * {@code SCRIPT TO}, through the {@code org.h2.tools.Script} tool of the H2 driver that LuckPerms
 * downloaded into {@code plugins/LuckPerms/libs/}. The cloud itself ships no H2.
 *
 * <p>Only for a stopped service: the running server holds the database file locked. The database
 * is opened read-only ({@code ACCESS_MODE_DATA=r}) and only if it exists ({@code IFEXISTS=TRUE}).
 * Paths containing {@code ;} are refused, because H2 would read the rest of the URL as settings.
 */
public final class H2Dumper {

    /** Main class of H2's script tool, which runs {@code SCRIPT TO}. */
    public static final String SCRIPT_TOOL = "org.h2.tools.Script";

    private final ToolRunner runner;
    private final Path java;
    private final Duration timeout;

    /**
     * An H2 database of a service.
     *
     * @param databaseFile the {@code .mv.db} file
     * @param driverJar the H2 driver jar
     */
    public record H2Database(Path databaseFile, Path driverJar) {

        /** Checks the values. */
        public H2Database {
            Objects.requireNonNull(databaseFile, "databaseFile");
            Objects.requireNonNull(driverJar, "driverJar");
        }

        /** The JDBC URL: the file without {@code .mv.db}, read-only, never created. */
        public String url() {
            String name = databaseFile.getFileName().toString();
            String base = name.substring(0, name.length() - ".mv.db".length());
            return "jdbc:h2:file:" + databaseFile.resolveSibling(base).toAbsolutePath()
                    + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        }
    }

    /**
     * Creates the dumper.
     *
     * @param runner runs the tool
     * @param java the Java executable that runs the H2 tool
     * @param timeout longest dump
     */
    public H2Dumper(ToolRunner runner, Path java, Duration timeout) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.java = Objects.requireNonNull(java, "java");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The Java executable of the running JVM. */
    public static Path currentJava() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    /**
     * Finds the LuckPerms H2 database of a service: a {@code luckperms-h2*.mv.db} file and an
     * {@code h2*.jar} below {@code plugins/LuckPerms/} (any case of the folder name, the proxy
     * plugin is {@code luckperms}). Links are not followed.
     *
     * @param serviceDirectory the service folder
     * @return the database, if both parts exist
     * @throws IOException if the folder cannot be read
     */
    public static Optional<H2Database> find(Path serviceDirectory) throws IOException {
        Path plugins = serviceDirectory.resolve("plugins");
        if (!Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path luckPerms = null;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(plugins)) {
            for (Path child : children) {
                if (child.getFileName().toString().equalsIgnoreCase("luckperms")
                        && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    luckPerms = child;
                    break;
                }
            }
        }
        if (luckPerms == null) {
            return Optional.empty();
        }
        Optional<Path> database = newest(luckPerms, "luckperms-h2", ".mv.db");
        Optional<Path> driver = newest(luckPerms.resolve("libs"), "h2", ".jar");
        if (database.isEmpty() || driver.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new H2Database(database.get(), driver.get()));
    }

    /**
     * The command line.
     *
     * @param database the database
     * @param resultFile where the script goes
     * @return program and arguments
     * @throws IllegalArgumentException if a path contains {@code ;}
     */
    public List<String> arguments(H2Database database, Path resultFile) {
        String url = database.url();
        if (database.databaseFile().toString().contains(";") || resultFile.toString().contains(";")) {
            throw new IllegalArgumentException("H2 paths must not contain ';'");
        }
        return List.of(java.toString(), "-cp", database.driverJar().toAbsolutePath().toString(), SCRIPT_TOOL,
                "-url", url, "-script", resultFile.toAbsolutePath().toString());
    }

    /**
     * Writes the SQL script of the database.
     *
     * @param database the database
     * @param resultFile the script file; replaced if it exists
     * @param workDirectory working directory of the tool
     * @throws BackupException with {@link BackupException.Reason#DATABASE} if it fails
     * @throws InterruptedException if interrupted
     */
    public void dump(H2Database database, Path resultFile, Path workDirectory)
            throws BackupException, InterruptedException {
        ToolRunner.Result result;
        try {
            Files.deleteIfExists(resultFile);
            result = runner.run(arguments(database, resultFile), workDirectory, timeout);
        } catch (IllegalArgumentException e) {
            throw new BackupException(BackupException.Reason.DATABASE, e.getMessage());
        } catch (IOException e) {
            throw new BackupException(BackupException.Reason.DATABASE, "H2 script tool could not run: "
                    + e.getMessage(), e);
        }
        if (result.exitCode() != 0 || !Files.isRegularFile(resultFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new BackupException(BackupException.Reason.DATABASE, "H2 SCRIPT TO failed with exit code "
                    + result.exitCode() + ": " + MariaDbDumper.tail(result.output()));
        }
    }

    private static Optional<Path> newest(Path folder, String prefix, String suffix) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix) && name.endsWith(suffix)
                        && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    matches.add(child);
                }
            }
        }
        // The highest version sorts last for names like h2-driver-2.1.214.jar and luckperms-h2-v2.mv.db.
        return matches.stream().max(Comparator.comparing(p -> p.getFileName().toString()));
    }
}
