package net.vectora.cloud.database;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;

/**
 * Mode {@code h2}: no database server. Every server keeps its data in embedded H2 files in its own
 * folder ({@code VECTORA_DB_STORAGE_METHOD=h2}); LuckPerms brings the H2 driver itself, so the cloud
 * needs no H2 library. Data is not shared between servers, so ranks set on one server do not show
 * up on another. Meant for tests, CI and a first look at the cloud on systems without a managed
 * MariaDB build.
 */
public final class EmbeddedProvisioner implements DatabaseProvisioner {

    private final DatabaseConfig config;
    private final Optional<String> notice;

    /**
     * Creates the provisioner.
     *
     * @param config database settings, mode {@code h2}; name and user still fill the templates
     */
    public EmbeddedProvisioner(DatabaseConfig config) {
        this(config, Optional.empty());
        if (config.mode() != DatabaseMode.H2) {
            throw new IllegalArgumentException("database mode h2 expected, got " + config.mode());
        }
    }

    private EmbeddedProvisioner(DatabaseConfig config, Optional<String> notice) {
        this.config = Objects.requireNonNull(config, "config");
        this.notice = notice;
    }

    /**
     * The embedded fallback for a configured mode that cannot run on this system, for example
     * {@code managed} on a platform without a MariaDB server build. The cloud starts anyway; the
     * notice tells the operator what changed and how to get a shared database.
     *
     * @param config the configured database settings; name and user still fill the templates
     * @param reason why the configured mode cannot run, ends with a full stop
     * @return the provisioner
     */
    public static EmbeddedProvisioner fallback(DatabaseConfig config, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new EmbeddedProvisioner(config, Optional.of(reason + " The cloud uses the embedded H2 fallback: every"
                + " server keeps its own LuckPerms files, so ranks are not shared between servers. For a shared"
                + " database set database.mode in cloud.yml to 'external' (your own MariaDB or MySQL) or 'docker'."));
    }

    @Override
    public DatabaseMode mode() {
        return DatabaseMode.H2;
    }

    @Override
    public DatabaseEndpoint provision() {
        return new DatabaseEndpoint(StorageMethod.H2, config.host(), config.port(), config.name(), config.user(),
                Optional.empty());
    }

    @Override
    public HealthReport health() {
        return new HealthReport(true, Duration.ZERO, notice.isPresent()
                ? "embedded H2 fallback, one set of files per server"
                : "embedded H2, one set of files per server");
    }

    @Override
    public Optional<String> notice() {
        return notice;
    }

    @Override
    public void shutdown() {
        // Nothing runs.
    }
}
