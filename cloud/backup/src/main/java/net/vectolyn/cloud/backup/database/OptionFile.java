package net.vectolyn.cloud.backup.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * A temporary MySQL/MariaDB client option file with user, password, host and port, passed to the
 * dump tool with {@code --defaults-extra-file}. The password therefore never appears in the
 * process arguments, which every local user can read ({@code ps}, Task Manager).
 *
 * <ul>
 *   <li>It lives in a new folder of its own, restricted to the owner before the file exists.</li>
 *   <li>On POSIX the file is created with {@code 600} in the same call; on Windows the empty file
 *       gets an owner-only, protected access list before the password is written.</li>
 *   <li>If the restriction does not hold (FAT, some network shares), nothing is written and the
 *       dump is refused.</li>
 *   <li>{@link #close()} deletes file and folder, also after a failed dump. A folder left behind by
 *       a JVM crash is removed by {@link #removeLeftovers(Path)} before the next dump.</li>
 * </ul>
 */
public final class OptionFile implements AutoCloseable {

    /** Prefix of the private folders. */
    public static final String FOLDER_PREFIX = ".dump-";

    /**
     * Characters a value in double quotes can carry without escapes: printable ASCII except
     * {@code "} and {@code \}.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile("[\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]{1,512}");
    private static final Pattern SAFE_WORD = Pattern.compile("[A-Za-z0-9_.:\\-\\[\\]]{1,255}");

    private final Path folder;
    private final Path file;

    private OptionFile(Path folder, Path file) {
        this.folder = folder;
        this.file = file;
    }

    /**
     * Writes the option file.
     *
     * @param parent folder to create the private folder in
     * @param user database user
     * @param password its password
     * @param host host
     * @param port port
     * @return the file; close it after the dump
     * @throws IOException if it cannot be written or restricted
     * @throws IllegalArgumentException if a value contains characters an option file cannot carry
     *     safely ({@code "}, {@code \}, control characters); the message never contains the value
     */
    public static OptionFile create(Path parent, String user, Secret password, String host, int port)
            throws IOException {
        Objects.requireNonNull(password, "password");
        if (user == null || !SAFE_WORD.matcher(user).matches()) {
            throw new IllegalArgumentException("the database user contains characters an option file cannot carry");
        }
        if (host == null || !SAFE_WORD.matcher(host).matches()) {
            throw new IllegalArgumentException("the database host contains characters an option file cannot carry");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("the database port is out of range");
        }
        String secret = password.reveal();
        if (!SAFE_VALUE.matcher(secret).matches()) {
            throw new IllegalArgumentException("the database password is empty or contains \", \\ or control "
                    + "characters, which an option file cannot carry safely");
        }
        Files.createDirectories(parent);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path folder = posix
                ? Files.createTempDirectory(parent, FOLDER_PREFIX,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                : Files.createTempDirectory(parent, FOLDER_PREFIX);
        OptionFile option = new OptionFile(folder, folder.resolve("client.cnf"));
        try {
            OwnerOnlyPermissions.restrictDirectory(folder);
            if (posix) {
                Files.createFile(option.file,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(option.file);
            }
            OwnerOnlyPermissions.restrictFile(option.file);
            if (!OwnerOnlyPermissions.isOwnerOnly(folder) || !OwnerOnlyPermissions.isOwnerOnly(option.file)) {
                throw new IOException("cannot restrict the option file to its owner on this file system");
            }
            String content = "[client]\n"
                    + "user=" + user + "\n"
                    + "password=\"" + secret + "\"\n"
                    + "host=" + host + "\n"
                    + "port=" + port + "\n"
                    + "protocol=TCP\n";
            Files.writeString(option.file, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return option;
        } catch (IOException | RuntimeException e) {
            option.close();
            throw e;
        }
    }

    /** The file, for {@code --defaults-extra-file}. */
    public Path file() {
        return file;
    }

    /** Deletes file and folder. Never throws. */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The folder stays then; removeLeftovers retries later.
        }
        try {
            Files.deleteIfExists(folder);
        } catch (IOException ignored) {
            // Retried by removeLeftovers.
        }
    }

    /**
     * Deletes private folders of earlier runs that did not end cleanly.
     *
     * @param parent the folder {@link #create} was called with
     */
    public static void removeLeftovers(Path parent) {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(parent, FOLDER_PREFIX + "*")) {
            for (Path folder : folders) {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(folder);
            }
        } catch (IOException ignored) {
            // Best effort; tried again before the next dump.
        }
    }
}
