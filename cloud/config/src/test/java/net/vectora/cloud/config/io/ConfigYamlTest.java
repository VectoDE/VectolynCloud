package net.vectora.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.vectora.cloud.config.Samples;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TimeSpan;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ConfigYamlTest {

    private static final String CLOUD = """
            schema-version: 1
            network-name: Vectora
            language: de
            eula-accepted: true
            setup-complete: false
            node-id: node-1
            servers:
              proxy: true
              lobby: true
              survival: false
            proxy:
              bind: 0.0.0.0
              port: 25565
              bedrock-port: 19132
            legacy-proxy:
              enabled: false
              port: 25564
            database:
              mode: docker
              host: 127.0.0.1
              port: 3307
              name: luckperms
              user: luckperms
              docker-image: mariadb:12.3
            api:
              enabled: false
              bind: 127.0.0.1
              port: 8085
              tls:
                enabled: false
            """;

    @Test
    void everyFormatRoundTrips() throws ConfigException {
        assertRoundTrip(CloudConfig.defaults(), ConfigFormat.CLOUD);
        assertRoundTrip(CloudConfig.defaults().withDatabase(DatabaseConfig.defaults().withMode(DatabaseMode.DOCKER)),
                ConfigFormat.CLOUD);
        assertRoundTrip(NodesConfig.defaults(), ConfigFormat.NODES);
        assertRoundTrip(Samples.lobbyGroup(), ConfigFormat.GROUP);
        assertRoundTrip(Samples.lobbyTask(), ConfigFormat.TASK);
    }

    @Test
    void writtenFilesUseKebabCaseLowerCaseEnumsAndHeader() {
        String yaml = new String(ConfigYaml.write(Samples.lobbyGroup()), StandardCharsets.UTF_8);
        assertTrue(yaml.startsWith(ConfigYaml.HEADER), yaml);
        assertTrue(yaml.contains("schema-version: 1"), yaml);
        assertTrue(yaml.contains("minecraft-version: \"26.3\""), "version must stay a string: " + yaml);
        assertTrue(yaml.contains("software: paper"), yaml);
        assertTrue(yaml.contains("java: auto"), yaml);
        assertTrue(yaml.contains("kind: transitional"), yaml);
        assertTrue(yaml.indexOf("schema-version") < yaml.indexOf("template"), "schema-version comes first");
        String cloud = new String(ConfigYaml.write(CloudConfig.defaults()), StandardCharsets.UTF_8);
        assertFalse(cloud.contains("docker-image"), "null fields are left out: " + cloud);
        assertFalse(cloud.contains("keystore"), cloud);
    }

    @Test
    void readsAHandWrittenFile() throws ConfigException {
        CloudConfig cloud = read(CLOUD, ConfigFormat.CLOUD);
        assertEquals(Language.DE, cloud.language());
        assertEquals(DatabaseMode.DOCKER, cloud.database().mode());
        assertEquals("mariadb:12.3", cloud.database().dockerImage());
        assertTrue(cloud.servers().lobby());
        assertFalse(cloud.servers().survival(), "a missing server counts as not wanted");
    }

    @Test
    void processSectionIsOptionalAndRoundTrips() throws ConfigException {
        String yaml = new String(ConfigYaml.write(Samples.lobbyGroup()), StandardCharsets.UTF_8);
        assertFalse(yaml.contains("process"), "a group without process section writes none: " + yaml);
        assertEquals(ProcessConfig.defaults(), Samples.lobbyGroup().processOrDefaults());

        GroupConfig tuned = Samples.lobbyGroup().withProcess(new ProcessConfig(List.of("-XX:+UseG1GC", "-Dfile.encoding=UTF-8"),
                "end", TimeSpan.parse("90s"), new RestartConfig(false, 3, TimeSpan.parse("10s"), TimeSpan.parse("2m"))));
        assertRoundTrip(tuned, ConfigFormat.GROUP);
        String written = new String(ConfigYaml.write(tuned), StandardCharsets.UTF_8);
        assertTrue(written.contains("stop-timeout: \"90s\""), written);
        assertTrue(written.contains("on-crash: false"), written);
    }

    @Test
    void partialProcessSectionGetsDefaultsButRestartNeedsEveryField() throws ConfigException {
        String base = new String(ConfigYaml.write(Samples.lobbyGroup()), StandardCharsets.UTF_8);
        GroupConfig group = read(base + """
                process:
                  jvm-flags: ["-XX:+UseZGC"]
                """, ConfigFormat.GROUP);
        assertEquals(List.of("-XX:+UseZGC"), group.process().jvmFlags());
        assertEquals(TimeSpan.parse("60s"), group.process().stopTimeout());
        assertEquals(RestartConfig.defaults(), group.process().restart());

        ConfigError error = single(base + """
                process:
                  restart:
                    max-attempts: 3
                    backoff: 5s
                    max-backoff: 1m
                """, ConfigFormat.GROUP);
        // The missing on-crash is a missing required field.
        assertEquals(ErrorCode.REQUIRED, error.code());
        assertEquals("process.restart.on-crash", error.path());
    }

    @Test
    void unknownFieldIsAnErrorWithPathAndLine() {
        ConfigError error = single(CLOUD.replace("  host: 127.0.0.1", "  hostt: 127.0.0.1"), ConfigFormat.CLOUD);
        assertEquals(ErrorCode.UNKNOWN_FIELD, error.code());
        assertEquals("database.hostt", error.path());
        assertEquals("cloud.yml", error.file());
        assertEquals(List.of("hostt"), error.args());
        assertEquals(20, error.line());
    }

    @Test
    void unknownFieldArgumentIsSanitized() {
        // YAML escapes: \e is ESC (ANSI colour), \n a line break, \u00e4 non-ASCII.
        ConfigError error = single(CLOUD + "\"ex\\e[31mtra\\nfake: line \\u00e4\": 1\n", ConfigFormat.CLOUD);
        assertEquals(ErrorCode.UNKNOWN_FIELD, error.code());
        assertEquals(List.of("ex?[31mtra?fake: line ?"), error.args());
        ConfigError longKey = single(CLOUD + "\"" + "x.".repeat(50) + "\": 1\n", ConfigFormat.CLOUD);
        assertEquals(List.of("x.".repeat(32)), longKey.args());
    }

    @Test
    void unknownTopLevelFieldIsAnError() {
        ConfigError error = single(CLOUD + "extra: 1\n", ConfigFormat.CLOUD);
        assertEquals(ErrorCode.UNKNOWN_FIELD, error.code());
        assertEquals("extra", error.path());
    }

    @Test
    void duplicateKeyIsAnError() {
        ConfigError error = single(CLOUD.replace("language: de\n", "language: de\nlanguage: en\n"), ConfigFormat.CLOUD);
        assertEquals(ErrorCode.DUPLICATE_FIELD, error.code());
    }

    @Test
    void wrongTypesAreErrorsAndNotGuessed() {
        assertEquals(ErrorCode.INVALID_VALUE,
                single(CLOUD.replace("port: 25565", "port: \"25565\""), ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.INVALID_VALUE,
                single(CLOUD.replace("port: 25565", "port: 25565.5"), ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.INVALID_VALUE,
                single(CLOUD.replace("eula-accepted: true", "eula-accepted: yes"), ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.INVALID_VALUE,
                single(CLOUD.replace("eula-accepted: true", "eula-accepted: 1"), ConfigFormat.CLOUD).code());
        ConfigError mode = single(CLOUD.replace("mode: docker", "mode: postgres"), ConfigFormat.CLOUD);
        assertEquals(ErrorCode.INVALID_VALUE, mode.code());
        assertEquals("database.mode", mode.path());
        assertEquals(List.of("one of managed, docker, external, h2"), mode.args());
    }

    @Test
    void unquotedMinecraftVersionIsRejectedInsteadOfBecomingANumber() {
        String group = """
                schema-version: 1
                template: Lobby-Server
                software: paper
                minecraft-version: 1.20
                java: 21
                heap: {min: 256M, max: 512M}
                combat: modern
                """;
        ConfigError error = single(group, ConfigFormat.GROUP);
        assertEquals(ErrorCode.INVALID_VALUE, error.code());
        assertEquals("minecraft-version", error.path());
        GroupConfig quoted = assertDoesNotFail(group.replace("1.20", "\"1.20\""), ConfigFormat.GROUP);
        assertEquals("1.20", quoted.minecraftVersion());
        assertEquals(21, quoted.java().major());
        assertTrue(quoted.catalog().isEmpty());
    }

    @Test
    void malformedValueTypesReportTheExpectedForm() {
        String task = """
                schema-version: 1
                group: lobby
                mode: dynamic
                scaling:
                  min-services: 1
                  max-services: 2
                  players-per-service: 50
                  scale-up-at: 80
                  scale-down-after: 5 minutes
                  cooldown: 60s
                """;
        ConfigError error = single(task, ConfigFormat.TASK);
        assertEquals(ErrorCode.INVALID_VALUE, error.code());
        assertEquals("scaling.scale-down-after", error.path());
    }

    @Test
    void listIndexesArePartOfThePath() {
        String group = """
                schema-version: 1
                template: Lobby-Server
                software: paper
                minecraft-version: "26.3"
                java: auto
                heap: {min: 256M, max: 512M}
                combat: modern
                catalog:
                  - id: luckperms
                    kind: transitional
                  - id: tab
                    kind: plugn
                """;
        ConfigError error = single(group, ConfigFormat.GROUP);
        assertEquals("catalog[1].kind", error.path());
    }

    @Test
    void schemaVersionMustBePresentAndSupported() {
        assertEquals(ErrorCode.SCHEMA_VERSION_MISSING,
                single(CLOUD.replace("schema-version: 1\n", ""), ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                single(CLOUD.replace("schema-version: 1", "schema-version: 2"), ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                single(CLOUD.replace("schema-version: 1", "schema-version: \"1\""), ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                single(CLOUD.replace("schema-version: 1", "schema-version: 0"), ConfigFormat.CLOUD).code());
    }

    @Test
    void syntaxErrorsAndNonMappingsAreReported() {
        assertEquals(ErrorCode.SYNTAX, single("a: [1, 2\nb: c", ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.NOT_A_MAPPING, single("", ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.NOT_A_MAPPING, single("- 1\n- 2\n", ConfigFormat.CLOUD).code());
        assertEquals(ErrorCode.NOT_A_MAPPING, single("just text", ConfigFormat.CLOUD).code());
        assertFalse(single("---\nschema-version: 1\n---\nschema-version: 1\n", ConfigFormat.NODES).code() == null);
    }

    @Test
    void oversizedFilesAreRejectedBeforeParsing() {
        byte[] big = new byte[ConfigMappers.MAX_FILE_BYTES + 1];
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigYaml.read("cloud.yml", big, ConfigFormat.CLOUD));
        assertEquals(ErrorCode.FILE_TOO_LARGE, e.errors().getFirst().code());
    }

    @Test
    void migrationsRunInOrderAndBumpTheVersion() throws ConfigException {
        ConfigMigration renameBind = new ConfigMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public void migrate(ObjectNode root) {
                ObjectNode proxy = (ObjectNode) root.get("proxy");
                proxy.set("bind", proxy.remove("address"));
            }
        };
        ConfigMigration noop = new ConfigMigration() {
            @Override
            public int fromVersion() {
                return 2;
            }

            @Override
            public void migrate(ObjectNode root) {
                root.put("schema-version", 99); // overwritten by the framework
            }
        };
        ConfigFormat<VersionedProxy> format = new ConfigFormat<>(VersionedProxy.class, 3, List.of(noop, renameBind));
        String old = """
                schema-version: 1
                proxy:
                  address: 0.0.0.0
                """;
        ConfigYaml.Decoded<VersionedProxy> decoded = ConfigYaml.read("x.yml", old.getBytes(StandardCharsets.UTF_8), format);
        assertEquals(1, decoded.fileVersion());
        assertEquals(new VersionedProxy(3, new VersionedProxy.Proxy("0.0.0.0")), decoded.value());
    }

    @Test
    void migrationChainMustBeComplete() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigFormat<>(String.class, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigFormat<>(String.class, 0, List.of()));
    }

    /** Test model for migrations. */
    record VersionedProxy(int schemaVersion, Proxy proxy) {
        record Proxy(String bind) {
        }
    }

    private static <T> void assertRoundTrip(T value, ConfigFormat<T> format) throws ConfigException {
        byte[] yaml = ConfigYaml.write(value);
        ConfigYaml.Decoded<T> decoded = ConfigYaml.read("test.yml", yaml, format);
        assertEquals(value, decoded.value(), new String(yaml, StandardCharsets.UTF_8));
        assertEquals(format.currentVersion(), decoded.fileVersion());
    }

    private static <T> T read(String yaml, ConfigFormat<T> format) throws ConfigException {
        return ConfigYaml.read(fileFor(format), yaml.getBytes(StandardCharsets.UTF_8), format).value();
    }

    private static <T> T assertDoesNotFail(String yaml, ConfigFormat<T> format) {
        try {
            return read(yaml, format);
        } catch (ConfigException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static ConfigError single(String yaml, ConfigFormat<?> format) {
        ConfigException e = assertThrows(ConfigException.class, () -> read(yaml, format));
        assertEquals(1, e.errors().size(), e.getMessage());
        return e.errors().getFirst();
    }

    private static String fileFor(ConfigFormat<?> format) {
        if (format == ConfigFormat.CLOUD) {
            return "cloud.yml";
        }
        if (format.type() == TaskConfig.class) {
            return "tasks/lobby.yml";
        }
        return format.type() == GroupConfig.class ? "groups/lobby.yml" : "nodes.yml";
    }

    @Test
    void groupSwitchIsWrittenOnlyWhenOff() throws ConfigException {
        GroupConfig on = Samples.lobbyGroup();
        assertTrue(on.wantedOrDefault());
        assertFalse(new String(ConfigYaml.write(on), StandardCharsets.UTF_8).contains("wanted"),
                "the default is not written");
        GroupConfig off = on.withWanted(false);
        assertFalse(off.wantedOrDefault());
        String written = new String(ConfigYaml.write(off), StandardCharsets.UTF_8);
        assertTrue(written.contains("wanted: false"), written);
        assertRoundTrip(off, ConfigFormat.GROUP);
        assertEquals(on, off.withWanted(true), "switching it on again removes the entry");
        assertEquals(on.withProcess(ProcessConfig.defaults()).withWanted(false).processOrDefaults(), ProcessConfig.defaults(),
                "the switch keeps the process section");
    }

    @Test
    void drainTimeoutIsOptionalInTheGroupFile() throws ConfigException {
        GroupConfig plain = Samples.lobbyGroup();
        String base = new String(ConfigYaml.write(plain), StandardCharsets.UTF_8);
        assertFalse(base.contains("drain-timeout"), "not written when missing: " + base);
        assertEquals(GroupConfig.DEFAULT_DRAIN_TIMEOUT, read(base, ConfigFormat.GROUP).drainTimeoutOrDefault());
        GroupConfig set = read(base + "drain-timeout: 45s\n", ConfigFormat.GROUP);
        assertEquals(TimeSpan.parse("45s"), set.drainTimeout());
        String written = new String(ConfigYaml.write(set), StandardCharsets.UTF_8);
        assertTrue(written.contains("drain-timeout: \"45s\""), written);
        assertRoundTrip(set, ConfigFormat.GROUP);
        assertEquals(set, plain.withDrainTimeout(TimeSpan.parse("45s")).withWanted(true), "the switch keeps it");
        // Counter-check: a value in the wrong form is invalid-value with the expected form.
        ConfigError bad = single(base + "drain-timeout: 45 seconds\n", ConfigFormat.GROUP);
        assertEquals(ErrorCode.INVALID_VALUE, bad.code());
        assertEquals("drain-timeout", bad.path());
        assertEquals(List.of(TimeSpan.FORMAT), bad.args());
    }

    @Test
    void groupSwitchAcceptsOnlyBooleans() {
        String base = new String(ConfigYaml.write(Samples.lobbyGroup()), StandardCharsets.UTF_8);
        assertEquals(ErrorCode.INVALID_VALUE, assertThrows(ConfigException.class,
                () -> read(base + "wanted: \"no\"\n", ConfigFormat.GROUP)).errors().getFirst().code());
    }

    @Test
    void legacyServerSwitchIsReadAndMissingCountsAsOff() throws ConfigException {
        String yaml = new String(ConfigYaml.write(CloudConfig.defaults()), StandardCharsets.UTF_8);
        assertTrue(yaml.contains("legacy-server: true"), yaml);
        CloudConfig without = read(yaml.replace("  legacy-server: true\n", ""), ConfigFormat.CLOUD);
        assertFalse(without.servers().isWanted(ServerKind.LEGACY_SERVER));
        assertTrue(without.servers().isWanted(ServerKind.MINIGAME));
    }
}
