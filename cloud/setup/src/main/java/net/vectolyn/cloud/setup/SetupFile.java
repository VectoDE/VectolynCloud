package net.vectolyn.cloud.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.MemoryReserve;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.TimeSpan;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.console.Text;

/**
 * The answers of the assistant as a YAML file, for starts that cannot ask (Docker, Pterodactyl, CI):
 * {@code java -jar VectolynCloud.jar --non-interactive --setup-file setup.yml}.
 *
 * <p>It is read as strictly as {@code cloud.yml} (unknown fields, duplicate keys, wrong types and
 * values in the wrong form are errors, see {@link ConfigMappers}), then turned into
 * {@link SetupAnswers} and checked by {@link SetupChecks} like the answers typed at the console.
 * Every key except {@code schema-version} and {@code eula} may be left out; it then keeps the value
 * the assistant would offer as default. The file holds no secret: the generated ones are created,
 * and the password of an external database comes from {@code secrets/vectolyn.env} or from the file
 * named in {@code database.password-file} (a Docker or Kubernetes secret, for example).
 *
 * @param schemaVersion version of the format, see {@link #SCHEMA_VERSION}
 * @param language      {@code de} or {@code en}
 * @param eula          must be {@code true}
 * @param networkName   display name
 * @param servers       wanted switches
 * @param minigames     the own switch of existing minigame groups, by group name
 * @param network       bind address and ports
 * @param memory        maximum heap per group name
 * @param services      static, minimum and maximum services per task name; a missing value keeps
 *                      the suggestion
 * @param drainTimeout  optional {@code drain-timeout} per group name (5s to 10m); a group without
 *                      an entry keeps what its file says, a new group gets the default 30s. The
 *                      console assistant asks for it under "advanced settings".
 * @param node          optional memory settings of this node in {@code nodes.yml}; the console
 *                      assistant asks for them under "advanced settings"
 * @param database      database settings
 */
public record SetupFile(int schemaVersion, Language language, Boolean eula, String networkName, Servers servers,
                        Map<String, Boolean> minigames, Network network, Map<String, MemorySize> memory,
                        Map<String, Services> services, Map<String, TimeSpan> drainTimeout, Node node,
                        Database database) {

    /** Current version of the format. */
    public static final int SCHEMA_VERSION = 1;
    /** Name used in error messages. */
    public static final String DISPLAY_NAME = "setup file";
    /** The format, read through the same strict reader as the configuration files. */
    public static final ConfigFormat<SetupFile> FORMAT = new ConfigFormat<>(SetupFile.class, SCHEMA_VERSION, List.of());

    /**
     * Switches under {@code servers:}; a missing one keeps its default.
     *
     * @param proxy        main proxy
     * @param lobby        lobby
     * @param survival     Survival
     * @param creative     Creative
     * @param mod          Mod-Server
     * @param end          End-Server
     * @param minigame     every minigame group
     * @param legacyProxy  legacy proxy for 1.8.8
     * @param legacyServer 1.8.8 server behind it
     */
    public record Servers(Boolean proxy, Boolean lobby, Boolean survival, Boolean creative, Boolean mod, Boolean end,
                          Boolean minigame, Boolean legacyProxy, Boolean legacyServer) {

        Boolean of(NetworkServer server) {
            return switch (server) {
                case PROXY -> proxy;
                case LOBBY -> lobby;
                case SURVIVAL -> survival;
                case CREATIVE -> creative;
                case MOD -> mod;
                case END -> end;
                case MINIGAME -> minigame;
                case LEGACY_PROXY -> legacyProxy;
                case LEGACY_SERVER -> legacyServer;
            };
        }
    }

    /**
     * Listener of the proxies.
     *
     * @param bind        bind address
     * @param javaPort    Java port of the main proxy
     * @param bedrockPort Bedrock port
     * @param legacyPort  port of the legacy proxy
     */
    public record Network(String bind, Integer javaPort, Integer bedrockPort, Integer legacyPort) {
    }

    /**
     * Service counts of one task, like in its task file; a missing one keeps the suggestion.
     *
     * @param staticServices {@code static-services}
     * @param minServices    {@code min-services}
     * @param maxServices    {@code max-services}
     */
    public record Services(Integer staticServices, Integer minServices, Integer maxServices) {

        ServiceCounts over(ServiceCounts defaults) {
            return new ServiceCounts(staticServices != null ? staticServices : defaults.staticServices(),
                    minServices != null ? minServices : defaults.minServices(),
                    maxServices != null ? maxServices : defaults.maxServices());
        }
    }

    /**
     * Memory settings of the node the setup runs on ({@code node-id} in {@code cloud.yml}). A missing
     * key keeps what {@code nodes.yml} says; a field of {@code memory-reserve} replaces only that
     * field.
     *
     * @param maxMemory     {@code max-memory}: a size of at least 512M, or {@value #AUTO} to remove the
     *                      limit so the node works it out
     * @param memoryReserve {@code memory-reserve}: {@code system}, {@code node-overhead},
     *                      {@code database}, each 1M to 64G
     */
    public record Node(String maxMemory, MemoryReserve memoryReserve) {

        /** Value of {@code max-memory} that removes the limit. */
        public static final String AUTO = "auto";
    }

    /**
     * Database settings.
     *
     * @param mode         {@code managed}, {@code docker}, {@code external} or {@code h2}
     * @param host         host of an external server
     * @param port         port of an external server
     * @param name         database name
     * @param user         database user
     * @param dockerImage  image for {@code docker}
     * @param passwordFile file that holds the password of an external server's user, relative to the
     *                     home or absolute; its content goes to {@code VECTOLYN_DB_PASSWORD}
     */
    public record Database(DatabaseMode mode, String host, Integer port, String name, String user, String dockerImage,
                           String passwordFile) {
    }

    /**
     * Parses a setup file.
     *
     * @throws ConfigException with the first problem (file {@value #DISPLAY_NAME}, path, line)
     */
    public static SetupFile parse(byte[] content) throws ConfigException {
        return ConfigYaml.read(DISPLAY_NAME, Objects.requireNonNull(content, "content"), FORMAT).value();
    }

    /**
     * Reads and parses {@code file}.
     *
     * @throws SetupException if it cannot be read or parsed; nothing is written
     */
    public static SetupFile read(Path file) throws SetupException {
        byte[] content;
        try {
            // Only a regular file (or a link to one); a FIFO or device would block or never end.
            if (!Files.isRegularFile(file)) {
                throw new SetupException(List.of(SetupMessages.FILE_UNREADABLE));
            }
            // Read at most one byte more than allowed: the size may change after a check, so the
            // limit is applied to what was actually read.
            try (InputStream in = Files.newInputStream(file)) {
                content = in.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
            }
        } catch (IOException | SecurityException e) {
            throw new SetupException(List.of(SetupMessages.FILE_UNREADABLE));
        }
        if (content.length > ConfigMappers.MAX_FILE_BYTES) {
            throw SetupException.of(new ConfigException(
                    ConfigError.of(DISPLAY_NAME, "", ErrorCode.FILE_TOO_LARGE, ConfigMappers.MAX_FILE_BYTES)));
        }
        try {
            return parse(content);
        } catch (ConfigException e) {
            throw SetupException.of(e);
        }
    }

    /**
     * The answers this file gives, on top of {@code defaults}.
     *
     * @param defaults      what the assistant would offer
     * @param passwordFiles reads a password file named in the file; returns the problem or the secret
     * @throws SetupException with every problem found in the file itself
     */
    public SetupAnswers toAnswers(SetupAnswers defaults,
                                  Function<String, PasswordFile.Result> passwordFiles) throws SetupException {
        List<Text> problems = new ArrayList<>();
        SetupAnswers answers = defaults;
        if (!Boolean.TRUE.equals(eula)) {
            problems.add(SetupMessages.FILE_EULA);
        }
        answers = answers.withEula(Boolean.TRUE.equals(eula));
        if (language != null) {
            answers = answers.withLanguage(language);
        }
        if (networkName != null) {
            answers = answers.withNetworkName(networkName);
        }
        if (servers != null) {
            for (NetworkServer server : NetworkServer.values()) {
                Boolean wanted = servers.of(server);
                if (wanted != null) {
                    answers = answers.withServer(server, wanted);
                }
            }
        }
        if (minigames != null) {
            for (Map.Entry<String, Boolean> entry : minigames.entrySet()) {
                if (entry.getValue() == null) {
                    problems.add(SetupMessages.fill(SetupMessages.FILE_MINIGAME_VALUE, ConfigNames.safeArgument(entry.getKey())));
                    continue;
                }
                answers = answers.withGroupSwitch(entry.getKey(), entry.getValue());
            }
        }
        if (network != null) {
            answers = answers.withNetwork(
                    network.bind() != null ? network.bind() : answers.bind(),
                    network.javaPort() != null ? network.javaPort() : answers.javaPort(),
                    network.bedrockPort() != null ? network.bedrockPort() : answers.bedrockPort(),
                    network.legacyPort() != null ? network.legacyPort() : answers.legacyPort());
        }
        if (memory != null) {
            for (Map.Entry<String, MemorySize> entry : memory.entrySet()) {
                String group = entry.getKey();
                if (!defaults.heap().containsKey(group) || entry.getValue() == null) {
                    problems.add(SetupMessages.fill(SetupMessages.FILE_UNKNOWN_MEMORY_GROUP, ConfigNames.safeArgument(group)));
                    continue;
                }
                answers = answers.withHeap(group, entry.getValue());
            }
        }
        if (services != null) {
            for (Map.Entry<String, Services> entry : services.entrySet()) {
                String task = entry.getKey();
                ServiceCounts suggested = defaults.services().get(task);
                if (suggested == null || entry.getValue() == null) {
                    problems.add(SetupMessages.fill(SetupMessages.FILE_UNKNOWN_SERVICES_TASK, ConfigNames.safeArgument(task)));
                    continue;
                }
                answers = answers.withServices(task, entry.getValue().over(suggested));
            }
        }
        if (drainTimeout != null) {
            for (Map.Entry<String, TimeSpan> entry : drainTimeout.entrySet()) {
                String group = entry.getKey();
                if (!defaults.heap().containsKey(group) || entry.getValue() == null) {
                    problems.add(SetupMessages.fill(SetupMessages.FILE_UNKNOWN_DRAIN_GROUP, ConfigNames.safeArgument(group)));
                    continue;
                }
                answers = answers.withDrainTimeout(group, entry.getValue());
            }
        }
        if (node != null) {
            MemorySize max = answers.maxMemory();
            if (Node.AUTO.equals(node.maxMemory())) {
                max = null;
            } else if (node.maxMemory() != null) {
                try {
                    max = MemorySize.parse(node.maxMemory());
                } catch (IllegalArgumentException e) {
                    problems.add(SetupMessages.FILE_NODE_MAX_MEMORY);
                }
            }
            MemoryReserve reserve = node.memoryReserve() != null ? node.memoryReserve().over(answers.memoryReserve())
                    : answers.memoryReserve();
            answers = answers.withNodeMemory(max, reserve);
        }
        if (database != null) {
            DatabaseConfig db = answers.database();
            if (database.mode() != null && database.mode() != db.mode()) {
                db = SetupPlan.switchMode(db, database.mode());
            }
            db = new DatabaseConfig(db.mode(),
                    database.host() != null ? database.host() : db.host(),
                    database.port() != null ? database.port() : db.port(),
                    database.name() != null ? database.name() : db.name(),
                    database.user() != null ? database.user() : db.user(),
                    db.mode() == DatabaseMode.DOCKER && database.dockerImage() != null ? database.dockerImage() : db.dockerImage());
            Optional<Secret> password = answers.databasePassword();
            if (database.passwordFile() != null) {
                PasswordFile.Result result = passwordFiles.apply(database.passwordFile());
                if (result.problem() != null) {
                    problems.add(result.problem());
                } else {
                    password = Optional.of(result.secret());
                }
            }
            answers = answers.withDatabase(db, password);
        }
        if (!problems.isEmpty()) {
            throw new SetupException(problems);
        }
        return answers;
    }

    /** Lists the keys only; the file holds no secret, but the habit stays. */
    @Override
    public String toString() {
        return "SetupFile[schemaVersion=" + schemaVersion + ", language=" + language + ", eula=" + eula + "]";
    }
}
