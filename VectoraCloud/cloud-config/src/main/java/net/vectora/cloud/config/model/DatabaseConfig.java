package net.vectora.cloud.config.model;

/**
 * Database server of the network. Passwords are not part of it: they live in
 * {@code secrets/vectora.env} ({@code VECTORA_DB_PASSWORD}, {@code VECTORA_DB_ROOT_PASSWORD}).
 *
 * @param mode        how the cloud gets the server
 * @param host        host of the server; for {@code managed} and {@code docker} the address it binds to
 * @param port        port of the server
 * @param name        database name ({@code VECTORA_DB_NAME})
 * @param user        database user ({@code VECTORA_DB_USER})
 * @param dockerImage container image, only for mode {@code docker} (for example {@code mariadb:11.8}); null otherwise
 */
public record DatabaseConfig(DatabaseMode mode, String host, int port, String name, String user, String dockerImage) {

    /** Image used when switching to Docker mode without naming one. */
    public static final String DEFAULT_DOCKER_IMAGE = "mariadb:11.8";

    /** Managed MariaDB on {@code 127.0.0.1:3307}, database and user {@code luckperms}. */
    public static DatabaseConfig defaults() {
        return new DatabaseConfig(DatabaseMode.MANAGED, "127.0.0.1", 3307, "luckperms", "luckperms", null);
    }

    /**
     * A copy in another mode. Switching to {@code docker} sets {@link #DEFAULT_DOCKER_IMAGE} unless an
     * image is already set; switching away from {@code docker} drops the image.
     */
    public DatabaseConfig withMode(DatabaseMode newMode) {
        String image = newMode == DatabaseMode.DOCKER ? (dockerImage != null ? dockerImage : DEFAULT_DOCKER_IMAGE) : null;
        return new DatabaseConfig(newMode, host, port, name, user, image);
    }
}
