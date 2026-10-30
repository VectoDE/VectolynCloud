package net.vectolyn.cloud.database.docker;

import java.util.regex.Pattern;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.database.ConnectionBudget;

/**
 * The {@code compose} file for database mode {@code docker}. The operator starts it; the cloud never
 * talks to the Docker socket.
 *
 * <ul>
 *   <li>The port is published on {@code 127.0.0.1} only.</li>
 *   <li>The root password is not in the file. Compose takes it from
 *       {@code secrets/vectolyn.env} ({@code --env-file}) and refuses to start without it.</li>
 *   <li>The application user is not created by the image (no {@code MARIADB_USER}); the cloud
 *       creates it with minimal rights once the container answers, as in the managed mode.</li>
 *   <li>Same server settings as the managed mode: {@code max_connections} 150, utf8mb4,
 *       {@code skip-name-resolve}.</li>
 * </ul>
 */
public final class DockerComposeFile {

    /** File name, next to the managed server's files in {@code <home>/Database/runtime/}. */
    public static final String FILE_NAME = "compose.database.yml";
    /** Name of the compose service and the volume. */
    public static final String SERVICE = "vectolyn-database";

    private static final Pattern IMAGE = Pattern.compile(ConfigNames.DOCKER_IMAGE);

    private DockerComposeFile() {
    }

    /**
     * Builds the file content.
     *
     * @param config database settings, mode {@code docker}
     * @return the YAML text
     */
    public static String render(DatabaseConfig config) {
        if (config.mode() != DatabaseMode.DOCKER) {
            throw new IllegalArgumentException("database mode docker expected, got " + config.mode());
        }
        String image = config.dockerImage() == null ? DatabaseConfig.DEFAULT_DOCKER_IMAGE : config.dockerImage();
        if (!IMAGE.matcher(image).matches()) {
            throw new IllegalArgumentException("database.docker-image is not a valid image reference");
        }
        int port = config.port();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("database.port must be between 1 and 65535");
        }
        return """
                # Written by VectolynCloud for database mode "docker". Changes here are lost; change the
                # database section of cloud.yml instead. Start it from this folder with:
                #   docker compose --env-file ../../secrets/vectolyn.env -f %2$s up -d
                # The cloud then creates the database and user and checks the connection.
                services:
                  %3$s:
                    image: "%1$s"
                    restart: unless-stopped
                    environment:
                      MARIADB_ROOT_PASSWORD: "${VECTOLYN_DB_ROOT_PASSWORD:?missing in secrets/vectolyn.env}"
                    command:
                      - "--max-connections=%5$d"
                      - "--character-set-server=utf8mb4"
                      - "--collation-server=utf8mb4_unicode_ci"
                      - "--skip-name-resolve"
                    ports:
                      - "127.0.0.1:%4$d:3306"
                    volumes:
                      - "%3$s:/var/lib/mysql"
                    healthcheck:
                      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
                      interval: 10s
                      timeout: 5s
                      retries: 6
                volumes:
                  %3$s: {}
                """.formatted(image, FILE_NAME, SERVICE, port, ConnectionBudget.MANAGED_MAX_CONNECTIONS);
    }

    /**
     * The command that starts the container, for messages.
     *
     * @return the command, run from {@code <home>/Database/runtime/}
     */
    public static String startCommand() {
        return "docker compose --env-file ../../secrets/vectolyn.env -f " + FILE_NAME + " up -d";
    }
}
