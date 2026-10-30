package net.vectolyn.cloud.database.managed;

import java.io.IOException;
import java.nio.file.Path;
import net.vectolyn.cloud.database.DatabaseException;

/**
 * Supplies an unpacked MariaDB server. {@link DownloadingMariaDbBinaries} downloads it through
 * {@code cloud-download}; tests use a folder they prepared.
 */
@FunctionalInterface
public interface MariaDbBinaries {

    /**
     * Makes sure the server is unpacked and returns its base directory.
     *
     * @param platform the platform
     * @return the base directory (the folder with {@code bin/} and {@code share/})
     * @throws DatabaseException if the server cannot be downloaded or unpacked
     * @throws IOException if reading or writing fails
     * @throws InterruptedException if interrupted
     */
    Path install(ManagedPlatform platform) throws DatabaseException, IOException, InterruptedException;
}
