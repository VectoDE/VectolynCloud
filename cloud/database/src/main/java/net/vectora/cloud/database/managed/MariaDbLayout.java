package net.vectora.cloud.database.managed;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Files of the managed server below its home, {@code <home>/Database/runtime/}. The names
 * are the ones the old start script used, so an existing local database is taken over as it is.
 *
 * <pre>
 * runtime/
 *   mariadb-11.8.9-winx64/      unpacked server (name from the archive)
 *   data/                       data directory
 *   vectora-mariadb.ini         configuration, written on every start
 *   vectora-init.sql            only while a start runs with it, then deleted
 *   vectora-init.pending        marker: the root password is not set yet, see {@link #bootstrapPending()}
 *   mariadb.log                 output of the server and of mariadb-install-db
 * </pre>
 *
 * @param home the home folder
 */
public record MariaDbLayout(Path home) {

    /** Name of the configuration file. */
    public static final String CONFIG_FILE = "vectora-mariadb.ini";
    /** Name of the first-start SQL file. */
    public static final String INIT_FILE = "vectora-init.sql";
    /** Name of the marker that says the init file still has to run. */
    public static final String INIT_PENDING = "vectora-init.pending";

    /**
     * Normalises the path.
     *
     * @param home the home folder
     */
    public MariaDbLayout {
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    }

    /**
     * Returns the data directory.
     *
     * @return {@code home/data}
     */
    public Path dataDir() {
        return home.resolve("data");
    }

    /**
     * Returns the configuration file.
     *
     * @return {@code home/vectora-mariadb.ini}
     */
    public Path configFile() {
        return home.resolve(CONFIG_FILE);
    }

    /**
     * Returns the first-start SQL file.
     *
     * @return {@code home/vectora-init.sql}
     */
    public Path initFile() {
        return home.resolve(INIT_FILE);
    }

    /**
     * Returns the marker file that is created before {@code mariadb-install-db} runs and deleted
     * once {@code root} logged in with the generated password.
     *
     * @return {@code home/vectora-init.pending}
     */
    public Path initPendingMarker() {
        return home.resolve(INIT_PENDING);
    }

    /**
     * Tells whether the data directory was created by the cloud but the init file never ran to the
     * end, for example because the first start failed. Until then {@code root} may have no
     * password, so every start passes the init file again.
     *
     * @return whether the marker exists
     */
    public boolean bootstrapPending() {
        return Files.exists(initPendingMarker(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Returns the log file.
     *
     * @return {@code home/mariadb.log}
     */
    public Path logFile() {
        return home.resolve("mariadb.log");
    }

    /**
     * Tells whether the data directory holds a database already ({@code data/mysql} exists).
     *
     * @return whether it is initialised
     */
    public boolean initialized() {
        return Files.isDirectory(dataDir().resolve("mysql"), LinkOption.NOFOLLOW_LINKS);
    }
}
