package net.vectolyn.cloud.database;

/**
 * Database setup: managed MariaDB, Docker or external server, plus central connection-pool sizing.
 *
 * <p>Entry points: {@link DatabaseProvisioners#create} and {@link ConnectionBudget}. Details in
 * docs/cloud/database.md.
 */
public final class CloudDatabaseModule {

    /** Gradle project name of this module. */
    public static final String NAME = "cloud-database";

    private CloudDatabaseModule() {
    }
}
