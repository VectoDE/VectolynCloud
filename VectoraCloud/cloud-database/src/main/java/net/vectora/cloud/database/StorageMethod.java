package net.vectora.cloud.database;

/**
 * What the servers write their data to. The value goes into {@code VECTORA_DB_STORAGE_METHOD}, which
 * the templates use as LuckPerms' {@code storage-method}.
 */
public enum StorageMethod {

    /** A MariaDB server (managed, Docker or external). */
    MARIADB("mariadb"),
    /** An external MySQL server. */
    MYSQL("mysql"),
    /**
     * Embedded H2 files, one set per server. Data is not shared between servers, so this is for
     * tests and for trying the cloud out.
     */
    H2("h2");

    private final String value;

    StorageMethod(String value) {
        this.value = value;
    }

    /**
     * Returns the value of {@code VECTORA_DB_STORAGE_METHOD}.
     *
     * @return {@code mariadb}, {@code mysql} or {@code h2}
     */
    public String placeholderValue() {
        return value;
    }
}
