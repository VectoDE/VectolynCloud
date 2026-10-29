package net.vectora.cloud.database.managed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.database.DatabaseProcesses.Command;

/**
 * The command lines of the managed server. Paths are separate arguments, so spaces in the
 * repository path need no quoting.
 */
public final class MariaDbCommands {

    private MariaDbCommands() {
    }

    /**
     * Command that creates the data directory.
     *
     * <ul>
     *   <li>Windows: {@code bin/mariadb-install-db.exe} (older archives: {@code mysql_install_db.exe})
     *       with {@code --datadir}. It creates {@code root@localhost} without a password; the first
     *       start sets it through {@link net.vectora.cloud.database.sql.BootstrapStatements#initFile}.</li>
     *   <li>Linux: {@code scripts/mariadb-install-db} with {@code --no-defaults}, {@code --basedir},
     *       {@code --datadir}, {@code --auth-root-authentication-method=normal} (no extra
     *       {@code unix_socket} super user for the operating system account) and
     *       {@code --skip-name-resolve}.</li>
     * </ul>
     *
     * @param platform the platform
     * @param base the server's base directory
     * @param layout the layout
     * @return the command
     * @throws DatabaseException if the archive holds no install program
     */
    public static Command initialize(ManagedPlatform platform, Path base, MariaDbLayout layout) throws DatabaseException {
        List<String> args = new ArrayList<>();
        if (platform == ManagedPlatform.WINDOWS_X64) {
            args.add(firstExisting(base, "bin/mariadb-install-db.exe", "bin/mysql_install_db.exe").toString());
            args.add("--datadir=" + layout.dataDir());
        } else {
            args.add(firstExisting(base, "scripts/mariadb-install-db", "scripts/mysql_install_db",
                    "bin/mariadb-install-db").toString());
            args.add("--no-defaults");
            args.add("--basedir=" + base);
            args.add("--datadir=" + layout.dataDir());
            args.add("--auth-root-authentication-method=normal");
            args.add("--skip-name-resolve");
        }
        return new Command(args, layout.home(), layout.logFile());
    }

    /**
     * Command that runs the server in the foreground. {@code --defaults-file} must come first.
     *
     * @param platform the platform
     * @param base the server's base directory
     * @param layout the layout
     * @param initFile SQL file to run before the first connection, only on the first start
     * @return the command
     */
    public static Command server(ManagedPlatform platform, Path base, MariaDbLayout layout, Optional<Path> initFile) {
        List<String> args = new ArrayList<>();
        args.add(base.resolve("bin").resolve(platform.executable("mariadbd")).toString());
        args.add("--defaults-file=" + layout.configFile());
        args.add("--basedir=" + base);
        args.add("--datadir=" + layout.dataDir());
        initFile.ifPresent(f -> args.add("--init-file=" + f));
        if (platform == ManagedPlatform.WINDOWS_X64) {
            // Log to the console (our log file) instead of <datadir>/<host>.err.
            args.add("--console");
        }
        return new Command(args, layout.home(), layout.logFile());
    }

    private static Path firstExisting(Path base, String... candidates) throws DatabaseException {
        for (String candidate : candidates) {
            Path p = base.resolve(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new DatabaseException(DatabaseException.Reason.INITIALIZE, "The MariaDB folder " + base
                + " holds none of " + String.join(", ", candidates) + "; delete it so the cloud unpacks it again.");
    }
}
